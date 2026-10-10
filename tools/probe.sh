#!/system/bin/sh
# Run on the phone (Termux):  su -c sh /data/local/tmp/probe.sh [folders|add <folderId> <component>]
# Logs go to /data/local/tmp/probe.log
LOG=/data/local/tmp/probe.log
cmd=${1:-folders}

logcat -c
if [ "$cmd" = "add" ]; then
    am broadcast -a com.auto.classify.PROBE --es cmd add --ei folderId "$2" --es component "$3"
else
    am broadcast -a com.auto.classify.PROBE --es cmd folders
fi
sleep 2
logcat -d -s AutoClassify:* LSPosed-Bridge:* | tee -a "$LOG"
