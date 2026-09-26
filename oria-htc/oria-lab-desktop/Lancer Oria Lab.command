#!/bin/zsh
set -eu
ORIA_LAB_DIR="${0:A:h}"
ORIA_LAB_PYTHON="$ORIA_LAB_DIR/../ml/.venv/bin/python"
if [[ ! -x "$ORIA_LAB_PYTHON" ]]; then
  print 'Environnement ml/.venv absent. Consulter le README Oria Lab.'
  read '?Appuyez sur Entrée pour fermer.'
  exit 1
fi
exec "$ORIA_LAB_PYTHON" "$ORIA_LAB_DIR/launch.py"
