#!/usr/bin/zsh

git add app
msg=$1
if [[ -z "$msg" ]]; then
    msg='incremental'
fi
git commit -m "$msg"
