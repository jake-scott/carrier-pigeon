#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Fails if UI code passes an English string literal straight to Compose or Glance, which Android
# lint's HardcodedText check doesn't see. Text belongs in res/values/*.xml so it can be translated.
#
# Flags Text("…"), Tag("…"), contentDescription = "…" and text = "…" under ui/ and widget/.
# A literal that is genuinely not language (a symbol, a technical name) can stay if its line ends
# with the comment // not-translated.
set -euo pipefail
cd "$(dirname "$0")/.."

src=app/src/main/java/dev/jakethesnake/carrierpigeon

# Perl reads each file whole, so Text( on one line and "…" on the next is caught too.
hits=$(find "$src/ui" "$src/widget" -name '*.kt' -print0 | xargs -0 perl -0777 -ne '
    while (/\b(?:Text|Tag)\(\s*"|\b(?:contentDescription|text)\s*=\s*"/g) {
        my $line = substr($_, 0, $-[0]) =~ tr/\n//;
        my $end = index($_, "\n", $+[0]);
        my $text = (split /\n/, $_, -1)[$line];
        my $last = substr($_, $+[0], ($end < 0 ? length($_) : $end) - $+[0]);
        next if "$text$last" =~ m{// not-translated};
        printf "%s:%d: %s\n", $ARGV, $line + 1, $text =~ s/^\s+//r;
    }
')
if [ -n "$hits" ]; then
    echo "Hard-coded UI strings found. Move them to app/src/main/res/values/strings.xml:"
    echo "$hits"
    exit 1
fi
echo "No hard-coded UI strings."
