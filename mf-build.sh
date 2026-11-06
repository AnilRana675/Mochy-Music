#!/usr/bin/env bash
# linux wrapper: upstream build.sh uses macos "sed -i ''", this shim makes it work with gnu sed
set -e
SHIM_DIR="$(mktemp -d)"
REAL_SED="$(command -v sed)"
cat > "$SHIM_DIR/sed" << SHIM
#!/usr/bin/env bash
# drop the empty string arg that follows -i (bsd style)
args=()
skip=0
for a in "\$@"; do
  if [ "\$skip" = 1 ]; then
    skip=0
    [ -z "\$a" ] && continue
  fi
  [ "\$a" = "-i" ] && skip=1
  args+=("\$a")
done
exec "$REAL_SED" "\${args[@]}"
SHIM
chmod +x "$SHIM_DIR/sed"
trap 'rm -rf "$SHIM_DIR"' EXIT
PATH="$SHIM_DIR:$PATH" ./build.sh "$@"
