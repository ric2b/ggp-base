# Simple script to check that a chosen move is acceptable.
#
# check_move.pl <Player Index> <;ok move 1;ok move 2;...;> <JSON match record filename>
#
use strict;

use JSON;

my $lPlayerIndex = shift;
my $lAcceptable = shift;

my $lRecord = decode_json <>;
my $lLastMove = $lRecord->{moves}[-1][$lPlayerIndex];
$lLastMove =~ s/^\( //;
$lLastMove =~ s/ \)$//;

if (index($lAcceptable, ";$lLastMove;") == -1)
{
  print STDERR "Unacceptable move: $lLastMove\n";
  exit 1;
}

exit 0;
