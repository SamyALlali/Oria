#!/bin/zsh
set -e
task_dir="${0:A:h}"
python3 "$task_dir/pull_capture.py" --open
printf '\nCapture copiée. Ouvrez son ZIP dans Oria Lab sur Mac.\n'
read -r '?Entrée pour fermer…'
