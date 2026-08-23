#!/bin/sh
# Model ids to offer for "Generate commit message · model", one per line.
#
# Called by JCode when that settings field appears, with the currently selected tool as $1. Which
# ids a tool accepts is this extension's business rather than the app's, which is why the lookup
# lives here in a file the extension ships instead of in a manifest string or in JCode itself.
#
# Silence is a valid answer. The field takes free text, so a tool that is not installed, or one with
# no way to enumerate its models, simply means no suggestions -- never an error.

tool="${1:-claude}"

case "$tool" in
  opencode)
    # `opencode models` prints provider/model per line, which is already the shape we want.
    command -v opencode >/dev/null 2>&1 || exit 0
    opencode models 2>/dev/null
    ;;

  claude)
    # Claude Code has no command that lists what it accepts, so these are written down. The three
    # aliases are what the CLI documents and what most people pass; the dated ids are for pinning a
    # specific snapshot. Typing something newer still works -- this is a list of suggestions.
    command -v claude >/dev/null 2>&1 || exit 0
    cat <<'IDS'
opus
sonnet
haiku
claude-opus-4-5
claude-sonnet-4-5
claude-haiku-4-5
IDS
    ;;

  *)
    # "custom" runs a command of the user's own; nothing here can know what it takes.
    ;;
esac

exit 0
