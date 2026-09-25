#!/bin/sh
# 사용: hooks/install.sh <대상 리포> — installDist 결과 경로를 박은 post-commit 훅 설치
# 경로를 박는 이유: 환경변수는 새 셸 · IDE 커밋에 없고, 없으면 훅이 로그 한 줄만 남기고 조용히 끝난다
set -e
[ $# -eq 1 ] || { echo "사용: $0 <대상 리포>" >&2; exit 1; }

# CDPATH 가 export 돼 있으면 cd 가 경로를 stdout 에 찍어 $(...) 에 섞인다
here="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
home="$(dirname "$here")/build/install/flowdiffmap"
[ -x "$home/bin/flowdiffmap" ] || { echo "$home 없음 — 먼저 ./gradlew installDist" >&2; exit 1; }

# 훅의 큰따옴표 문자열 안에 들어가므로 \ $ ` " 이스케이프 → 그다음 sed 치환문용 & | \ 이스케이프
esc="$(printf '%s' "$home" | sed 's/[\\$`"]/\\&/g; s/[&|\\]/\\&/g')"
hook="$(sed "s|\$FLOWDIFFMAP_HOME|$esc|" "$here/post-commit")"

# --git-path: core.hooksPath · worktree(.git 이 파일)에서도 git 이 실제로 읽는 훅 폴더
hooks="$(git -C "$1" rev-parse --path-format=absolute --git-path hooks)"
gitdir="$(git -C "$1" rev-parse --path-format=absolute --git-common-dir)"
top="$(git -C "$1" rev-parse --path-format=absolute --show-toplevel)"
case "$hooks/" in
    "$gitdir"/*) ;;
    "$top"/*) reason="작업 트리 안의 훅 폴더(core.hooksPath) — 이 PC 경로가 대상 리포에 커밋될 자리" ;;
esac
# 이 스크립트가 설치한 훅(주석 머리 그대로)은 다시 덮는다 — 폴더를 옮긴 뒤 재설치로 경로 갱신
if [ -z "${reason:-}" ] && [ -e "$hooks/post-commit" ] && ! grep -q '^# flowdiffmap' "$hooks/post-commit"; then
    reason="다른 post-commit 훅 존재"
fi
if [ -n "${reason:-}" ]; then
    # 기존 훅 끝이 exit · exec 면 그 뒤에 붙인 줄은 돌지 않는다
    echo "$hooks/post-commit 설치 안 함 — $reason · 기존 훅의 exit · exec 앞에 아래 줄 직접 추가:" >&2
    printf '%s\n' "$hook" | tail -n 1 >&2
    exit 1
fi

mkdir -p "$hooks"
printf '%s\n' "$hook" > "$hooks/post-commit"
chmod +x "$hooks/post-commit"
echo "설치: $hooks/post-commit"
