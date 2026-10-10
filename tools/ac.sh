#!/system/bin/sh
# Termux:  su  then  sh /data/local/tmp/ac.sh <reclassify|reload|folders|status>
logcat -c
am broadcast -a com.auto.classify.COMMAND -p com.sec.android.app.launcher --es cmd "${1:-status}" >/dev/null
sleep "${2:-3}"
logcat -d -s AutoClassify:* | tee -a /data/local/tmp/ac.log
