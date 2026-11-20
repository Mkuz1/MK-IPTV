#!/bin/zsh
# usage: render.sh file.html out.png W H
CH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
UD=/private/tmp/claude-501/-Users-mkoussa--claude-spares-spare-iAz4fO/1521e8f8-1c12-41e7-8f2f-8587119216bc/scratchpad/chrome-render
mkdir -p $UD
"$CH" --headless=new --user-data-dir=$UD --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --default-background-color=00000000 --virtual-time-budget=1500 \
  --window-size=$3,$4 --screenshot="$2" "file://$1" >/dev/null 2>&1
