#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import re
import shlex
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QProcess, Slot
from PySide6.QtGui import QDragEnterEvent, QDropEvent
from PySide6.QtWidgets import (
    QApplication,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QPushButton,
    QLabel,
    QLineEdit,
    QFileDialog,
    QTextEdit,
    QMessageBox,
    QGroupBox,
)
import subprocess
import shutil

def ts() -> str:
    return time.strftime("%H:%M:%S")


def is_windows() -> bool:
    return sys.platform.startswith("win")


def open_path_in_os(path: str) -> None:
    try:
        if sys.platform.startswith("win"):
            os.startfile(path)  # type: ignore[attr-defined]
            return

        if sys.platform == "darwin":
            subprocess.run(["open", path], check=False)
            return

        # Linux: try multiple openers, prefer KDE ones if present (avoid portal issues)
        candidates = []
        if shutil.which("kioclient5"):
            candidates.append(["kioclient5", "exec", path])
        if shutil.which("kioclient"):
            candidates.append(["kioclient", "exec", path])
        if shutil.which("gio"):
            candidates.append(["gio", "open", path])
        if shutil.which("xdg-open"):
            candidates.append(["xdg-open", path])

        for cmd in candidates:
            try:
                # suppress noisy stderr from portal layers
                r = subprocess.run(cmd, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if r.returncode == 0:
                    return
            except Exception:
                pass
    except Exception:
        pass

def guess_output_ext(input_path: str) -> str:
    p = input_path.lower()
    if p.endswith(".gpx"):
        return "ms"
    if p.endswith(".ms"):
        return "gpx"
    return ""


def make_default_output_path(input_path: str) -> str:
    inp = Path(input_path)
    out_ext = guess_output_ext(str(inp))
    if not out_ext:
        return str(inp.with_suffix(".out"))
    return str(inp.with_suffix(f".{out_ext}"))


def is_supported_input(path: str) -> bool:
    lower = path.lower()
    return lower.endswith(".gpx") or lower.endswith(".ms")


@dataclass
class RunRequest:
    input_path: str
    output_path: str
    append: bool = False


def parse_stats(text: str) -> tuple[int | None, int | None]:
    """
    Parse converter output to extract:
      - added_count
      - skipped_duplicates
    Returns (added, skipped) where each can be None if not found.

    Supported formats (examples):
      "Append: +12, duplicates skipped: 12"
      "added objects: 12"
      "duplicates skipped: 12"
      "added=12 skipped=12"
    """
    if not text:
        return None, None

    added: int | None = None
    skipped: int | None = None

    # Primary expected format
    m = re.search(r"Append:\s*\+(\d+)\s*,\s*duplicates\s+skipped:\s*(\d+)", text, re.IGNORECASE)
    if m:
        return int(m.group(1)), int(m.group(2))

    # More flexible patterns
    m = re.search(r"\badded(?:\s+objects)?\s*[:=]\s*\+?(\d+)\b", text, re.IGNORECASE)
    if m:
        added = int(m.group(1))

    m = re.search(r"\bduplicates\s+skipped\s*[:=]\s*(\d+)\b", text, re.IGNORECASE)
    if m:
        skipped = int(m.group(1))

    # Compact "added=.. skipped=.." (or similar)
    m = re.search(r"\badded\s*=\s*\+?(\d+)\b", text, re.IGNORECASE)
    if m:
        added = int(m.group(1))
    m = re.search(r"\bskipped(?:_duplicates)?\s*=\s*(\d+)\b", text, re.IGNORECASE)
    if m:
        skipped = int(m.group(1))

    return added, skipped


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("GPX2MS (PySide6)")

        # Enable drag & drop on the whole window
        self.setAcceptDrops(True)

        self.converter_path = str(Path(__file__).with_name("converter3.py"))

        self.input_edit = QLineEdit()
        self.output_edit = QLineEdit()

        self.btn_pick_input = QPushButton("Pick input (.gpx / .ms)…")
        self.btn_pick_output = QPushButton("Pick output…")

        self.btn_convert = QPushButton("Convert")
        self.btn_append = QPushButton("Append into existing .ms…")

        self.btn_open_output = QPushButton("Open output")
        self.btn_open_folder = QPushButton("Open output folder")
        self.btn_clear_log = QPushButton("Clear log")

        self.log_view = QTextEdit()
        self.log_view.setReadOnly(True)

        # Summary UI
        self.lbl_added = QLabel("Added: —")
        self.lbl_skipped = QLabel("Duplicates skipped: —")

        self.btn_convert.setEnabled(False)
        self.btn_append.setEnabled(False)
        self.btn_open_output.setEnabled(False)
        self.btn_open_folder.setEnabled(False)

        # QProcess state
        self.proc: QProcess | None = None
        self._last_output_path: str | None = None
        self._run_req: RunRequest | None = None
        self._t0: float | None = None
        self._stdout_accum: list[str] = []

        # Layout
        root = QVBoxLayout(self)

        box_io = QGroupBox("Files (you can also drag & drop .gpx / .ms into the window)")
        io = QVBoxLayout(box_io)

        row_in = QHBoxLayout()
        row_in.addWidget(QLabel("Input:"))
        row_in.addWidget(self.input_edit, 1)
        row_in.addWidget(self.btn_pick_input)
        io.addLayout(row_in)

        row_out = QHBoxLayout()
        row_out.addWidget(QLabel("Output:"))
        row_out.addWidget(self.output_edit, 1)
        row_out.addWidget(self.btn_pick_output)
        io.addLayout(row_out)

        root.addWidget(box_io)

        box_actions = QGroupBox("Actions")
        actions = QHBoxLayout(box_actions)
        actions.addWidget(self.btn_convert)
        actions.addWidget(self.btn_append)
        actions.addStretch(1)
        root.addWidget(box_actions)

        box_summary = QGroupBox("Summary")
        summ = QHBoxLayout(box_summary)
        summ.addWidget(self.lbl_added)
        summ.addStretch(1)
        summ.addWidget(self.lbl_skipped)
        root.addWidget(box_summary)

        box_post = QGroupBox("After run")
        post = QHBoxLayout(box_post)
        post.addWidget(self.btn_open_output)
        post.addWidget(self.btn_open_folder)
        post.addStretch(1)
        post.addWidget(self.btn_clear_log)
        root.addWidget(box_post)

        root.addWidget(QLabel("Log:"))
        root.addWidget(self.log_view, 1)

        # Signals
        self.btn_pick_input.clicked.connect(self.pick_input)
        self.btn_pick_output.clicked.connect(self.pick_output)
        self.btn_convert.clicked.connect(self.on_convert)
        self.btn_append.clicked.connect(self.on_append)
        self.btn_open_output.clicked.connect(self.on_open_output)
        self.btn_open_folder.clicked.connect(self.on_open_folder)
        self.btn_clear_log.clicked.connect(self.on_clear_log)

        self.input_edit.textChanged.connect(self.on_paths_changed)
        self.output_edit.textChanged.connect(self.on_paths_changed)

        self.log(f"[{ts()}] GUI started")
        self.log(f"[{ts()}] sys.executable = {sys.executable}")
        self.log(f"[{ts()}] converter_path = {self.converter_path} (exists={os.path.exists(self.converter_path)})")
        self.log(f"[{ts()}] cwd = {os.getcwd()}")

    # ---------- drag & drop ----------

    def dragEnterEvent(self, event: QDragEnterEvent) -> None:
        md = event.mimeData()
        if md.hasUrls():
            # Accept if any URL looks like a supported file
            for u in md.urls():
                p = u.toLocalFile()
                if p and is_supported_input(p):
                    event.acceptProposedAction()
                    return
        event.ignore()

    def dropEvent(self, event: QDropEvent) -> None:
        md = event.mimeData()
        if not md.hasUrls():
            return

        # Pick first supported file
        for u in md.urls():
            p = u.toLocalFile()
            if p and is_supported_input(p):
                self.set_input_path(p)
                event.acceptProposedAction()
                return

    def set_input_path(self, path: str) -> None:
        self.input_edit.setText(path)
        self.output_edit.setText(make_default_output_path(path))
        self.log(f"[{ts()}] Dropped: {path}")

    # ---------- logging/UI helpers ----------

    def log(self, s: str):
        self.log_view.append(s)

    def set_busy(self, busy: bool):
        self.btn_pick_input.setEnabled(not busy)
        self.btn_pick_output.setEnabled(not busy)
        self.btn_clear_log.setEnabled(not busy)

        self.btn_convert.setEnabled(not busy and self.can_convert())
        self.btn_append.setEnabled(not busy and self.can_append())

        self.btn_open_output.setEnabled(
            (not busy) and bool(self._last_output_path) and os.path.exists(self._last_output_path or "")
        )
        self.btn_open_folder.setEnabled((not busy) and bool(self._last_output_path))

    def can_convert(self) -> bool:
        inp = self.input_edit.text().strip()
        out = self.output_edit.text().strip()
        return bool(inp and out and os.path.exists(inp) and is_supported_input(inp))

    def can_append(self) -> bool:
        inp = self.input_edit.text().strip().lower()
        return bool(inp.endswith(".gpx") and os.path.exists(self.input_edit.text().strip()))

    def set_summary(self, added: int | None, skipped: int | None):
        self.lbl_added.setText(f"Added: {added if added is not None else '—'}")
        self.lbl_skipped.setText(f"Duplicates skipped: {skipped if skipped is not None else '—'}")

    # ---------- file pickers ----------

    @Slot()
    def on_paths_changed(self):
        running = self.proc is not None
        self.btn_convert.setEnabled(self.can_convert() and not running)
        self.btn_append.setEnabled(self.can_append() and not running)

    @Slot()
    def pick_input(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "Pick input file", "", "GPS files (*.gpx *.ms);;All files (*.*)"
        )
        if not path:
            return
        self.set_input_path(path)

    @Slot()
    def pick_output(self):
        suggested = self.output_edit.text().strip() or ""
        start_dir = str(Path(suggested).parent) if suggested else ""
        path, _ = QFileDialog.getSaveFileName(self, "Pick output file", start_dir, "All files (*.*)")
        if not path:
            return
        self.output_edit.setText(path)

    # ---------- process runner (QProcess) ----------

    def start_process(self, req: RunRequest):
        if self.proc is not None:
            return

        if not os.path.exists(self.converter_path):
            QMessageBox.warning(self, "Error", f"converter3.py not found:\n{self.converter_path}")
            return
        if not os.path.exists(req.input_path):
            QMessageBox.warning(self, "Error", f"Input not found:\n{req.input_path}")
            return
        if not is_supported_input(req.input_path):
            QMessageBox.warning(self, "Error", "Unsupported input. Please select .gpx or .ms")
            return

        self._run_req = req
        self._last_output_path = req.output_path
        self._t0 = time.time()
        self._stdout_accum = []

        # Reset summary before run
        self.set_summary(None, None)

        self.log("----")
        self.log(f"[{ts()}] Run requested append={req.append}")

        args = ["-u", self.converter_path, req.input_path, req.output_path]
        if req.append:
            args.append("--append")

        p = QProcess(self)
        self.proc = p

        p.setProgram(sys.executable)
        p.setArguments(args)
        p.setWorkingDirectory(os.getcwd())
        p.setProcessChannelMode(QProcess.MergedChannels)

        p.readyReadStandardOutput.connect(self.on_ready_read)
        p.finished.connect(self.on_finished)
        p.errorOccurred.connect(self.on_error)

        self.set_busy(True)
        p.start()

        if not p.waitForStarted(3000):
            self.log(f"[{ts()}] ERROR: process did not start")
            self.set_busy(False)
            self.proc = None
            QMessageBox.warning(self, "Error", "Process did not start")
            return

        self.log(f"[{ts()}] pid = {p.processId()}")

    @Slot()
    def on_ready_read(self):
        if self.proc is None:
            return
        data = bytes(self.proc.readAllStandardOutput())
        if not data:
            return
        text = data.decode("utf-8", errors="replace")
        self._stdout_accum.append(text)

        # Print chunk line-by-line
        for line in text.splitlines():
            self.log(line)

    @Slot(int, QProcess.ExitStatus)
    def on_finished(self, exit_code: int, exit_status: QProcess.ExitStatus):
        elapsed = (time.time() - self._t0) if self._t0 else 0.0
        self.log(f"[{ts()}] FINISH exit_code={exit_code} status={exit_status.name} elapsed={elapsed:.3f}s")

        full_out = "".join(self._stdout_accum)
        added, skipped = parse_stats(full_out)
        self.set_summary(added, skipped)

        self.set_busy(False)

        if exit_code != 0:
            QMessageBox.warning(self, "Converter error", f"Exit code {exit_code}\n\nSee log for details.")

        # release process
        if self.proc is not None:
            self.proc.deleteLater()
        self.proc = None
        self._run_req = None
        self._t0 = None
        self._stdout_accum = []

        # re-evaluate buttons
        self.on_paths_changed()

    @Slot(QProcess.ProcessError)
    def on_error(self, err: QProcess.ProcessError):
        self.log(f"[{ts()}] QProcess error: {err.name}")
        self.set_busy(False)
        if self.proc is not None:
            self.proc.deleteLater()
        self.proc = None
        QMessageBox.warning(self, "Process error", f"{err.name}\n\nSee log for details.")
        self.on_paths_changed()

    # ---------- actions ----------

    @Slot()
    def on_convert(self):
        inp = self.input_edit.text().strip()
        out = self.output_edit.text().strip()
        if not inp or not out:
            return
        self.start_process(RunRequest(inp, out, append=False))

    @Slot()
    def on_append(self):
        inp = self.input_edit.text().strip()
        if not inp.lower().endswith(".gpx"):
            QMessageBox.information(self, "Append", "Append works only when input is .gpx")
            return

        target, _ = QFileDialog.getOpenFileName(
            self,
            "Pick target .ms to append into",
            "",
            "MS files (*.ms);;All files (*.*)"
        )
        if not target:
            return

        self.output_edit.setText(target)
        self.start_process(RunRequest(inp, target, append=True))

    @Slot()
    def on_open_output(self):
        p = self._last_output_path
        if p and os.path.exists(p):
            open_path_in_os(p)

    @Slot()
    def on_open_folder(self):
        p = self._last_output_path
        if not p:
            return
        open_path_in_os(str(Path(p).parent))

    @Slot()
    def on_clear_log(self):
        self.log_view.clear()
        self.log(f"[{ts()}] log cleared")


def main():
    app = QApplication(sys.argv)
    w = MainWindow()
    w.resize(900, 620)
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()