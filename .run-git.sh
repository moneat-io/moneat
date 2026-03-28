#!/bin/sh
cd /Users/aelder/.auto-agent/worktrees/Moneat/fix-issue-283 || exit 1
/usr/bin/git diff --stat
echo "---SEPARATOR---"
/usr/bin/git diff backend/detekt.yml
echo "---SEPARATOR---"
/usr/bin/git log --oneline -10
echo "---SEPARATOR---"
/usr/bin/git log origin/develop..HEAD --oneline
