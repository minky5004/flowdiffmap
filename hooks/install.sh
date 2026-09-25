#!/bin/sh
# 사용: hooks/install.sh <대상 리포> — installDist 결과 경로를 박은 post-commit 훅 설치
# 경로를 박는 이유: 환경변수는 새 셸 · IDE 커밋에 없고, 없으면 훅이 로그 한 줄만 남기고 조용히 끝난다
set -e
[ $# -eq 1 ] || { echo "사용: $0 <대상 리포>" >&2; exit 1; }

here="$(cd "$(dirname "$0")" && pwd)"
home="$(dirname "$here")/build/install/flowdiffmap"
[ -x "$home/bin/flowdiffmap" ] || { echo "$home 없음 — 먼저 ./gradlew installDist" >&2; exit 1; }

# --git-path: core.hooksPath · worktree(.git 이 파일)에서도 git 이 실제로 읽는 훅 폴더
hooks="$(git -C "$1" rev-parse --path-format=absolute --git-path hooks)"
if [ -e "$hooks/post-commit" ]; then
    echo "$hooks/post-commit 이미 있음 — 덮어쓰지 않음 · 기존 훅 끝에 아래 줄 추가:" >&2
    echo "\"$home/bin/flowdiffmap\" >> \"\$(git rev-parse --git-dir)/flowdiffmap.log\" 2>&1 || true" >&2
    exit 1
fi

mkdir -p "$hooks"
# sed 치환문 안에서 특수한 & | \ 이스케이프
esc="$(printf '%s' "$home" | sed 's/[&|\\]/\\&/g')"
sed "s|\$FLOWDIFFMAP_HOME|$esc|" "$here/post-commit" > "$hooks/post-commit"
chmod +x "$hooks/post-commit"
echo "설치: $hooks/post-commit"
