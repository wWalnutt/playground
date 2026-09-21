#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${TMUX_PANE:-}" ]]; then
  printf 'This menu must run inside tmux.\n' >&2
  exit 1
fi

SESSION="$(tmux display-message -p -t "$TMUX_PANE" '#{session_id}')"

while true; do
  printf '\033[2J\033[H'
  printf '=== Playground AI / tmux ===\n\n'
  printf '1. Close this project session\n'
  printf '2. Detach (keep the application running)\n\n'
  printf 'Closing stops this session and its application.\n'
  printf 'Other sessions and Docker containers are not stopped.\n\n'
  if ! read -r -p 'Choose [1/2]: ' choice; then
    exit 0
  fi
  case "$choice" in
    1)
      if ! read -r -p 'Close this session and its application? [y/N]: ' confirm; then
        exit 0
      fi
      case "$confirm" in
        y|Y)
          tmux kill-session -t "$SESSION"
          exit 0
          ;;
      esac
      ;;
    2)
      tmux detach-client -s "$SESSION"
      ;;
    *)
      printf 'Invalid choice. Enter 1 or 2.\n'
      sleep 1
      ;;
  esac
done
