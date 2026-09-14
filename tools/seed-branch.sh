#!/usr/bin/env bash
# Stand up the branch node the phone joins, with one nurse's day on it.
#
#   NURSE=rn-sm-s918u tools/seed-branch.sh [dir] [sync-port] [ui-port]
#
# NURSE is the node name the app shows in its status line ("rn-" plus the
# handset model). The visits are addressed to it, so the day appears on that
# phone and on no other.
#
# Order matters. The merge policy for a collection is declared BEFORE two
# clinicians write to the same document, so both halves of a controlled-
# substance disposal land on one record. Declared afterwards, the collection
# has already forked and the two halves sit on two copies.
set -euo pipefail

DIR=${1:-$HOME/hospice-branch}
SYNC=${2:-47810}
UI=${3:-7485}
LIB=hospice-demo
NURSE=${NURSE:?set NURSE to the node name the app shows, e.g. rn-sm-s918u}
BIN=${UNIDATUM:-unidatum}
TODAY=$(date +%F)

mkdir -p "$DIR"
cd "$DIR"
"$BIN" init --library "$LIB" --node-name branch-1

# Create each collection, then declare per-field merge on it. Two clinicians
# writing different fields of one document then keep both writes.
for c in patients visits medications visit_verification; do
  "$BIN" doc put "$c" '{"_id":"seed","note":"collection created"}'
  "$BIN" doc merge "$c"
done

put() { "$BIN" doc put "$1" "$2"; }

put patients '{"_id":"p-1041","name":"Margaret Dolan","address":"14 Mill Lane, Carrick",
  "diagnosis":"end-stage COPD","level_of_care":"routine","benefit_period":3,
  "medicine_list":[{"drug":"Morphine sulfate","dose":"5 mg","route":"sublingual"},
                   {"drug":"Lorazepam","dose":"0.5 mg","route":"sublingual"},
                   {"drug":"Glycopyrrolate","dose":"0.2 mg","route":"subcutaneous"}]}'
put patients '{"_id":"p-1042","name":"Arthur Whelan","address":"3 Hillcrest, Ferrybank",
  "diagnosis":"metastatic prostate cancer","level_of_care":"routine","benefit_period":2,
  "medicine_list":[{"drug":"Oxycodone","dose":"10 mg","route":"oral"},
                   {"drug":"Ondansetron","dose":"4 mg","route":"oral"}]}'
put patients '{"_id":"p-1043","name":"Bridget Kearns","address":"Rosslare Road, Tagoat",
  "diagnosis":"end-stage heart failure","level_of_care":"continuous","benefit_period":5,
  "medicine_list":[{"drug":"Morphine sulfate","dose":"2.5 mg","route":"sublingual"},
                   {"drug":"Furosemide","dose":"40 mg","route":"oral"}]}'

put visits "{\"_id\":\"v-$TODAY-1\",\"date\":\"$TODAY\",\"clinician_id\":\"$NURSE\",\"patient_id\":\"p-1041\",\"kind\":\"routine\",\"status\":\"scheduled\",\"scheduled_at\":\"${TODAY}T09:30:00Z\"}"
put visits "{\"_id\":\"v-$TODAY-2\",\"date\":\"$TODAY\",\"clinician_id\":\"$NURSE\",\"patient_id\":\"p-1042\",\"kind\":\"routine\",\"status\":\"scheduled\",\"scheduled_at\":\"${TODAY}T11:15:00Z\"}"
put visits "{\"_id\":\"v-$TODAY-3\",\"date\":\"$TODAY\",\"clinician_id\":\"$NURSE\",\"patient_id\":\"p-1043\",\"kind\":\"prn\",\"status\":\"scheduled\",\"scheduled_at\":\"${TODAY}T14:00:00Z\"}"

echo
echo "Seeded. Now run the node and leave it running:"
echo "  cd $DIR && $BIN ui --port $SYNC --ui-port $UI --bind 0.0.0.0 --sql --seed-open --sync-every 5"
echo
echo "Then put this machine's address in the app's BRANCH field and tap Rejoin."
echo "$NURSE has three visits on $TODAY."
