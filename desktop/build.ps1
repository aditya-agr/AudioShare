# Builds dist\AudioShare.exe (one file, no console). Run from this folder: .\build.ps1
python -m pip install -r requirements.txt pyinstaller
python -m PyInstaller --noconfirm --onefile --windowed --name AudioShare --icon icon.ico `
    --add-data "ui.html;." --collect-data soundcard audioshare.py
