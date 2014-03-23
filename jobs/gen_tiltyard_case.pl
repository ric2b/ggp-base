use strict;

use JSON;
use LWP::Simple;

#*****************************************************************************#
#* Order in which keys should appear.                                        *#
#*****************************************************************************#
my %gKeyOrder = (
                  # In the top level case
                  case => 0,
                  repo => 1,
                  game => 2,
                  start => 3,
                  play => 4,
                  limit => 5,
                  players => 6,
                  check => 7,

                  # Within a player
                  type => 0,
                  args => 1,

                  # Within the check
                  player => 0,
                  acceptable => 1
                 );

#*****************************************************************************#
#* Get a match ID.                                                           *#
#*****************************************************************************#
print "Match ID: ";
my $lMatchID = <STDIN>;
chomp($lMatchID);

if ($lMatchID !~ /^[0-9a-f]{30,50}$/)
{
  die "Match ID should be the ID part of the URL when viewing on Tiltyard.\n";
}

#*****************************************************************************#
#* Load the match record.                                                    *#
#*****************************************************************************#
my $lRecord = do
{
  local $/;
  my $lURL = "http://matches.ggp.org/matches/$lMatchID";
  print "Loading match record from $lURL\n";
  my $lJSON = get($lURL);
  die "Failed to load match record from $lURL\n" unless defined $lJSON;
  decode_json $lJSON;
};

my $lGameURL = $lRecord->{gameMetaURL};
my $lRepo;
my $lGame;
if ($lGameURL =~ m#http://(games.ggp.org/base)/games/([^/]+)/#)
{
  $lRepo = $1;
  $lGame = $2;
}
else
{
  die "Couldn't understand game URL: $lGameURL\n";
}

my $lStartTimeLocal = localtime($lRecord->{startTime} / 1000);
print "This was a game of '$lGame' (from $lRepo) between the following players at $lStartTimeLocal.\n";

#*****************************************************************************#
#* Get the player index.                                                     *#
#*****************************************************************************#
my $lNumPlayers = scalar(@{$lRecord->{playerNamesFromHost}});
my $lDefault = 0;

for (my $lii = 0; $lii < $lNumPlayers; $lii++)
{
  print "$lii: " . ($lRecord->{playerNamesFromHost})->[$lii] . "\n";
  $lDefault = $lii if ($lRecord->{playerNamesFromHost})->[$lii] =~ /sancho/i;
}

my $lPlayerIndex = getNumericParameter("Player index to test",
                                       0,
                                       $lNumPlayers - 1,
                                       $lDefault);

my $lPlayerName = ($lRecord->{playerNamesFromHost})->[$lPlayerIndex];
print $lPlayerName . " made the following moves.\n";

#*****************************************************************************#
#* Get the move number.                                                      *#
#*****************************************************************************#
my $lNumMoves = scalar(@{$lRecord->{moves}});
my $lMoveIndex = -1;

for (my $lii = 0; $lii < $lNumMoves; $lii++)
{
  my $lMove = ($lRecord->{moves})->[$lii]->[$lPlayerIndex];
  $lMove =~ s/^\( //;
  $lMove =~ s/ \)$//;
  print '' . ($lii + 1) . ": $lMove\n";
}

while (($lMoveIndex < 0) ||
       ($lMoveIndex > $lNumMoves - 1) ||
       ($lMoveIndex !~ /^[0-9]+$/))
{
  print "Please select a move number to test: ";
  $lMoveIndex = <STDIN>;
  chomp($lMoveIndex);
  $lMoveIndex--;
}

#*****************************************************************************#
#* Get the acceptable moves.                                                 *#
#*****************************************************************************#
my $lMove = ($lRecord->{moves})->[$lMoveIndex]->[$lPlayerIndex];
  $lMove =~ s/^\( //;
  $lMove =~ s/ \)$//;
print "$lPlayerName played '$lMove'.  What should Sancho have played?\n> ";
my $lAcceptable = <STDIN>;
chomp($lAcceptable);

#*****************************************************************************#
#* Get the linked bug number.                                                *#
#*****************************************************************************#
my $lBugNum = getNumericParameter(
                      'Enter the bug number for this test case, or 0 for none',
                      0,
                      9999,
                      0);

my $lBugStr = "";
if ($lBugNum > 0)
{
  $lBugStr = "Bug #$lBugNum, ";
}

#*****************************************************************************#
#* Get the clocks.                                                           *#
#*****************************************************************************#
my $lStartClock =
           getNumericParameter('Start clock', 10, 600, $lRecord->{startClock});

my $lPlayClock =
              getNumericParameter('Play clock', 1, 600, $lRecord->{playClock});

#*****************************************************************************#
#* Generate a test case.                                                     *#
#*****************************************************************************#
my $lCase = {};
$lCase->{case} = "${lBugStr}Tiltyard $lMatchID, player $lPlayerIndex, move " . ($lMoveIndex + 1);
$lCase->{repo} = $lRepo;
$lCase->{game} = $lGame;
$lCase->{start} = $lStartClock;
$lCase->{play} = $lPlayClock;
$lCase->{limit} = $lMoveIndex + 1;

$lCase->{players} = ();
for (my $lii = 0; $lii < $lNumPlayers; $lii++)
{
  $lCase->{players}->[$lii] = {};

  my $lFixedMoves;
  my $lArg;
  if ($lii == $lPlayerIndex)
  {
    $lCase->{players}->[$lii]->{type} = 'Sancho';
    $lFixedMoves = $lMoveIndex;
    $lArg .= "plan=";
  }
  else
  {
    $lCase->{players}->[$lii]->{type} = 'ScriptedPlayer';
    $lFixedMoves = $lMoveIndex + 1;
    $lArg = "";
  }

  if ($lFixedMoves > 0)
  {
    #*************************************************************************#
    #* Calculate the fixed moves for this player.                            *#
    #*************************************************************************#
    my @lPlayerMoves;
    for (my $ljj = 0; $ljj < $lFixedMoves; $ljj++)
    {
      my $lMove = ($lRecord->{moves})->[$ljj]->[$lii];
      $lMove =~ s/^\( //;
      $lMove =~ s/ \)$//;
      push(@lPlayerMoves, $lMove);
    }

    $lArg .= join(',', @lPlayerMoves);
    $lCase->{players}->[$lii]->{args} = ();
    $lCase->{players}->[$lii]->{args}->[0] = $lArg;
  }
}

$lCase->{check} = {};
$lCase->{check}->{player} = $lPlayerIndex;
$lCase->{check}->{acceptable} = $lAcceptable;

#*****************************************************************************#
#* Wrap the test case in a test suite.                                       *#
#*****************************************************************************#
my $lSuite = {};
$lSuite->{cases} = ();
$lSuite->{cases}->[0] = $lCase;

#*****************************************************************************#
#* Write the suite to file.                                                  *#
#*****************************************************************************#
my $lShortName = "Tiltyard.$lMatchID.$lPlayerIndex." . ($lMoveIndex + 1) . ".json";
my $lSuiteFile = "..\\data\\tests\\suites\\$lShortName";
open(SUITE, ">$lSuiteFile") or die "Failed to open $lSuiteFile: $!\n";
print SUITE JSON::PP->new->pretty->indent_length(2)->sort_by('customSort')->encode($lSuite);
close(SUITE);

print "Test case saved to $lSuiteFile\n";
print "Run with: run_cases.pl $lShortName\n\n";
exit 0;

#*****************************************************************************#
#*                                                                           *#
#* getNumericParameter                                                       *#
#*                                                                           *#
#* Purpose: Prompt the user for a number.                                    *#
#*                                                                           *#
#* Params:  IN     xiPrompt  - prompt to display (without default)           *#
#*          IN     xiMin     - minimum acceptable answer                     *#
#*          IN     xiMax     - maximum acceptable answer                     *#
#*          IN     xiDefault - default (if user presses enter)               *#
#*                                                                           *#
#* Returns: The chosen answer.                                               *#
#*                                                                           *#
#*****************************************************************************#

sub getNumericParameter
{
  my ($xiPrompt, $xiMin, $xiMax, $xiDefault) = @_;

  my $lAnswer = $xiMin - 1;
  while (($lAnswer < $xiMin) || ($lAnswer > $xiMax))
  {
    print "$xiPrompt [$xiDefault]: ";
    $lAnswer = <STDIN>;
    chomp($lAnswer);
    $lAnswer = $xiDefault if $lAnswer eq "";
  }

  return $lAnswer;
}

#*****************************************************************************#
#*                                                                           *#
#* customSort                                                                *#
#*                                                                           *#
#* Purpose: Sort routine to get the JSON file in a sensible order.           *#
#*                                                                           *#
#*****************************************************************************#
sub JSON::PP::customSort
{
  #***************************************************************************#
  #* Find the key order for the two keys.                                    *#
  #***************************************************************************#
  my $lAOrder = $gKeyOrder{$JSON::PP::a};
  my $lBOrder = $gKeyOrder{$JSON::PP::b};

  #***************************************************************************#
  #* Shove anything we've forgotten at the end.                              *#
  #***************************************************************************#
  $lAOrder = 100 if not defined $lAOrder;
  $lBOrder = 100 if not defined $lBOrder;

  #***************************************************************************#
  #* Compare the keys.                                                       *#
  #***************************************************************************#
  return $lAOrder <=> $lBOrder;
}

