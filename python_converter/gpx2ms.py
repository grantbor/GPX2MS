#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import shutil
import subprocess
import sys
import time
import traceback
from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal, Slot, QSettings
from PySide6.QtGui import QDragEnterEvent, QDropEvent, QColor
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
    QColorDialog,
    QFrame,
    QCheckBox,
)


def ts() -> str:
    return time.strftime("%H:%M:%S")


def is_windows() -> bool:
    return sys.platform.startswith("win")


def open_path_in_os(path: str) -> None:
    """
    Open a file/folder in the OS file manager.
    Linux: avoid portal-related issues by trying multiple openers.
    """
    try:
        if is_windows():
            os.startfile(path)  # type: ignore[attr-defined]
            return

        if sys.platform == "darwin":
            subprocess.run(["open", path], check=False)
            return

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


def is_ms_output_path(path: str) -> bool:
    return path.strip().lower().endswith(".ms")


def normalize_hex_color(s: str) -> str:
    """
    Accept #RRGGBB or #AARRGGBB. If invalid -> default cyan.
    """
    t = (s or "").strip()
    if len(t) == 7 and t.startswith("#"):
        return t.upper()
    if len(t) == 9 and t.startswith("#"):
        return t.upper()
    return "#00FFFF"


@dataclass
class RunRequest:
    input_path: str
    output_path: str
    append: bool = False
    style: str = ""  # "" means default/keep existing (see converter3.py semantics)


class WorkerSignals(QObject):
    log = Signal(str)
    done = Signal(str, int, int)  # message, added, skipped
    error = Signal(str)


class ConvertWorker(QRunnable):
    """
    Runs converter3.convert_file(...) in a background thread.
    """

    def __init__(self, req: RunRequest):
        super().__init__()
        self.req = req
        self.signals = WorkerSignals()

    @Slot()
    def run(self) -> None:
        try:
            self.signals.log.emit(f"[{ts()}] START append={self.req.append}")

            # Import inside worker: PyInstaller needs --hidden-import converter3
            import converter3  # type: ignore

            res = converter3.convert_file(  # type: ignore[attr-defined]
                self.req.input_path,
                self.req.output_path,
                append=bool(self.req.append),
                style=self.req.style,
            )

            msg = getattr(res, "message", "Done.")
            added = int(getattr(res, "added_count", 0) or 0)
            skipped = int(getattr(res, "skipped_duplicates", 0) or 0)

            self.signals.done.emit(msg, added, skipped)
        except Exception:
            self.signals.error.emit(traceback.format_exc())


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("GPX2MS (PySide6)")

        # Enable drag & drop on the whole window
        self.setAcceptDrops(True)

        self.input_edit = QLineEdit()
        self.output_edit = QLineEdit()

        self.btn_pick_input = QPushButton("Pick input (.gpx / .ms)…")
        self.btn_pick_output = QPushButton("Pick output…")

        self.btn_convert = QPushButton("Convert")
        self.btn_append = QPushButton("Append into existing .ms…")

        # Color UI
        self.btn_color = QPushButton("Color…")
        self.color_swatch = QFrame()
        self.color_swatch.setFixedSize(18, 18)
        self.color_swatch.setFrameShape(QFrame.Shape.StyledPanel)
        self.color_swatch.setFrameShadow(QFrame.Shadow.Sunken)

        self.chk_apply_color_on_append = QCheckBox("Apply color on append")
        self.chk_apply_color_on_append.setToolTip(
            "If enabled, append will overwrite <style> in the target .ms (recolors the whole file). "
            "If disabled, append keeps the existing style."
        )

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

        # Thread pool
        self.pool = QThreadPool.globalInstance()
        self._busy = False

        # Last output path (for open buttons)
        self._last_output_path: str | None = None

        # Settings (persist color + checkbox)
        self.settings = QSettings("GPX2MS", "gpx2ms")
        saved_color = self.settings.value("color_hex", "#00FFFF")
        self.color_hex = normalize_hex_color(str(saved_color))
        saved_apply = self.settings.value("apply_color_on_append", False)
        self.chk_apply_color_on_append.setChecked(bool(saved_apply))

        self.apply_color_swatch(self.color_hex)

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

        actions.addSpacing(18)
        actions.addWidget(self.btn_color)

        sw_wrap = QHBoxLayout()
        sw_wrap.setContentsMargins(0, 0, 0, 0)
        sw_wrap.addWidget(self.color_swatch)
        sw_wrap.addStretch(1)
        actions.addLayout(sw_wrap)

        actions.addSpacing(12)
        actions.addWidget(self.chk_apply_color_on_append)

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

        self.btn_color.clicked.connect(self.on_pick_color)
        self.chk_apply_color_on_append.stateChanged.connect(self.on_apply_color_on_append_changed)

        self.btn_open_output.clicked.connect(self.on_open_output)
        self.btn_open_folder.clicked.connect(self.on_open_folder)
        self.btn_clear_log.clicked.connect(self.on_clear_log)

        self.input_edit.textChanged.connect(self.on_paths_changed)
        self.output_edit.textChanged.connect(self.on_paths_changed)

        self.log(f"[{ts()}] GUI started")
        self.log(f"[{ts()}] sys.executable = {sys.executable}")
        self.log(f"[{ts()}] cwd = {os.getcwd()}")

    # ---------- drag & drop ----------

    def dragEnterEvent(self, event: QDragEnterEvent) -> None:
        md = event.mimeData()
        if md.hasUrls():
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
        self._busy = busy

        self.btn_pick_input.setEnabled(not busy)
        self.btn_pick_output.setEnabled(not busy)
        self.btn_clear_log.setEnabled(not busy)

        self.btn_convert.setEnabled(not busy and self.can_convert())
        self.btn_append.setEnabled(not busy and self.can_append())

        self.btn_color.setEnabled(not busy)
        self.chk_apply_color_on_append.setEnabled(not busy)

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

    def apply_color_swatch(self, hex_color: str) -> None:
        c = normalize_hex_color(hex_color)
        self.color_swatch.setStyleSheet(f"background-color: {c}; border: 1px solid rgba(0,0,0,0.25);")

    def pick_color_hex(self) -> str:
        # QColorDialog expects #RRGGBB; ignore alpha if present
        initial = normalize_hex_color(self.color_hex)
        initial_rgb = initial
        if len(initial_rgb) == 9:  # #AARRGGBB
            initial_rgb = "#" + initial_rgb[3:]
        q0 = QColor(initial_rgb)

        col = QColorDialog.getColor(q0, self, "Objects color")
        if not col.isValid():
            return self.color_hex

        # store as #RRGGBB
        return col.name(QColor.NameFormat.HexRgb).upper()

    # ---------- file pickers ----------

    @Slot()
    def on_paths_changed(self):
        if self._busy:
            return
        self.btn_convert.setEnabled(self.can_convert())
        self.btn_append.setEnabled(self.can_append())

    @Slot()
    def pick_input(self):
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Pick input file",
            str(Path.home()),
            "GPS files (*.gpx *.ms);;All files (*.*)",
            options=QFileDialog.Option.DontUseNativeDialog,
        )
        if not path:
            return
        self.set_input_path(path)

    @Slot()
    def pick_output(self):
        suggested = self.output_edit.text().strip() or str(Path.home())
        start_dir = str(Path(suggested).parent) if suggested else str(Path.home())
        path, _ = QFileDialog.getSaveFileName(
            self,
            "Pick output file",
            start_dir,
            "All files (*.*)",
            options=QFileDialog.Option.DontUseNativeDialog,
        )
        if not path:
            return
        self.output_edit.setText(path)

    # ---------- color ----------

    @Slot()
    def on_pick_color(self):
        new_hex = self.pick_color_hex()
        new_hex = normalize_hex_color(new_hex)
        if new_hex == self.color_hex:
            return
        self.color_hex = new_hex
        self.apply_color_swatch(self.color_hex)
        self.settings.setValue("color_hex", self.color_hex)
        self.log(f"[{ts()}] Color set to {self.color_hex}")

    @Slot()
    def on_apply_color_on_append_changed(self):
        self.settings.setValue("apply_color_on_append", self.chk_apply_color_on_append.isChecked())

    # ---------- runner ----------

    def _style_for_request(self, req: RunRequest) -> str:
        """
        converter3.py semantics:
          - GPX->MS convert: pass "#RRGGBB" (converter expands to MapCSS).
          - GPX->MS append: style="" keeps existing style; pass color only if checkbox enabled.
          - MS->GPX: ignore style (pass "").
        """
        out_is_ms = is_ms_output_path(req.output_path)
        inp_is_gpx = req.input_path.strip().lower().endswith(".gpx")

        if not out_is_ms:
            return ""  # MS->GPX

        if not req.append:
            return self.color_hex

        if self.chk_apply_color_on_append.isChecked() and inp_is_gpx:
            return self.color_hex
        return ""  # keep existing MS style

    def start_worker(self, req: RunRequest):
        if self._busy:
            return

        if not os.path.exists(req.input_path):
            QMessageBox.warning(self, "Error", f"Input not found:\n{req.input_path}")
            return

        self.set_summary(None, None)
        self._last_output_path = req.output_path

        req.style = self._style_for_request(req)

        worker = ConvertWorker(req)
        worker.signals.log.connect(self.log)
        worker.signals.done.connect(self.on_worker_done)
        worker.signals.error.connect(self.on_worker_error)

        self.set_busy(True)
        self.pool.start(worker)

    @Slot(str, int, int)
    def on_worker_done(self, message: str, added: int, skipped: int):
        self.log(f"[{ts()}] {message}")
        self.log(f"[{ts()}] FINISH")
        self.set_summary(added, skipped)
        self.set_busy(False)
        self.on_paths_changed()

    @Slot(str)
    def on_worker_error(self, tb: str):
        self.log(tb)
        self.set_busy(False)
        self.on_paths_changed()
        QMessageBox.warning(self, "Converter error", "Conversion failed.\n\nSee log for details.")

    # ---------- actions ----------

    @Slot()
    def on_convert(self):
        inp = self.input_edit.text().strip()
        out = self.output_edit.text().strip()
        if not inp or not out:
            return
        self.start_worker(RunRequest(inp, out, append=False))

    @Slot()
    def on_append(self):
        inp = self.input_edit.text().strip()
        if not inp.lower().endswith(".gpx"):
            QMessageBox.information(self, "Append", "Append works only when input is .gpx")
            return

        target, _ = QFileDialog.getOpenFileName(
            self,
            "Pick target .ms to append into",
            str(Path.home()),
            "MS files (*.ms);;All files (*.*)",
            options=QFileDialog.Option.DontUseNativeDialog,
        )
        if not target:
            return

        self.output_edit.setText(target)
        self.start_worker(RunRequest(inp, target, append=True))

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
    w.resize(980, 650)
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
