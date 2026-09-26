#!/bin/zsh
set -eu
ECHOTEST_DIR="${0:A:h}"
ECHOTEST_PYTHON="$ECHOTEST_DIR/../ml/.venv/bin/python"
if [[ ! -x "$ECHOTEST_PYTHON" ]]; then
  print 'Environnement ml/.venv absent. Consulter le README Oria Lab.'
  read '?Appuyez sur Entrée pour fermer.'
  exit 1
fi
exec "$ECHOTEST_PYTHON" "$ECHOTEST_DIR/launch.py"
