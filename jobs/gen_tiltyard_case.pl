use strict;

use JSON;
use Data::Dumper; # !! ARR Remove me
use LWP::Simple;

# f2a734a9519a4ab089e04463d2f711728b4ea4e6

#*****************************************************************************#
#* Get a match ID.                                                           *#
#*****************************************************************************#
print "Match ID: ";
my $lMatchID = <STDIN>;
chomp($lMatchID);

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

for (my $lii = 0; $lii < $lNumPlayers; $lii++)
{
  print "$lii: " . ($lRecord->{playerNamesFromHost})->[$lii] . "\n";
}

my $lPlayerIndex = -1;
while (($lPlayerIndex < 0) ||
       ($lPlayerIndex > $lNumPlayers - 1) ||
       ($lPlayerIndex !~ /^[0-9]+$/))
{
  print "Player index to test: ";
  $lPlayerIndex = <STDIN>;
  chomp($lPlayerIndex);
}

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
#* Generate a test case.                                                     *#
#*****************************************************************************#
my $lCase = {};
$lCase->{case} = "Regression for Tiltyard match $lMatchID, move " . ($lMoveIndex + 1);
$lCase->{repo} = $lRepo;
$lCase->{game} = $lGame;
$lCase->{start} = $lRecord->{startClock};
$lCase->{play} = $lRecord->{playClock};
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

print to_json($lCase, {pretty => 1});

