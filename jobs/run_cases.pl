use strict;

use JSON;

my $lSuite = do {local $/; decode_json <>};

foreach my $lCase (@{$lSuite->{cases}})
{
  print "Testing: $lCase->{case}...";

  #***************************************************************************#
  #* Make sure there isn't anything left lying around from the last run.     *#
  #***************************************************************************#
  unlink('record.json');

  #***************************************************************************#
  #* Start the players.                                                      *#
  #***************************************************************************#
  my $lPort = 9147;
  foreach my $lPlayer (@{$lCase->{players}})
  {
    $lPlayer->{port} = $lPort++;
    my @lSysArgs = ("player", $lPlayer->{port}, $lPlayer->{type});
    defined($lPlayer->{args}) && push(@lSysArgs, @{$lPlayer->{args}});
    system(@lSysArgs);
  }

  #***************************************************************************#
  #* Start the server.                                                       *#
  #***************************************************************************#
  my @lSysArgs = ("server",
                  $lCase->{repo},
                  $lCase->{game},
                  $lCase->{start},
                  $lCase->{play},
                  $lCase->{limit});
  foreach my $lPlayer (@{$lCase->{players}})
  {
    push(@lSysArgs, "127.0.0.1", $lPlayer->{port}, $lPlayer->{type});
  }
  system(@lSysArgs);

  #***************************************************************************#
  #* Display the result.                                                     *#
  #***************************************************************************#
  my @lRecords = glob('..\bin\oneshot\*.json');
  my $lResult = checkAcceptable($lCase->{check}->{player},
                                $lCase->{check}->{acceptable},
                                $lRecords[0]);
  print "$lResult\n";
}

#*****************************************************************************#
#* Check that the move played was acceptable.                                *#
#*****************************************************************************#
sub checkAcceptable
{
  my ($xiPlayerIndex, $xiAcceptable, $xiFilename) = @_;

  #***************************************************************************#
  #* Read the record.                                                        *#
  #***************************************************************************#
  open(RECORD, "<$xiFilename") or die "Failed to open $xiFilename: $!\n";
  my $lRecord = do {local $/; decode_json <RECORD>};
  close(RECORD);

  #***************************************************************************#
  #* Extract the last move for the specified player.                         *#
  #***************************************************************************#
  my $lLastMove = $lRecord->{moves}[-1][$xiPlayerIndex];
  $lLastMove =~ s/^\( //;
  $lLastMove =~ s/ \)$//;

  #***************************************************************************#
  #* Check if the move is in the acceptable list.                            *#
  #***************************************************************************#
  if (index(",$xiAcceptable,", ",$lLastMove,") == -1)
  {
    return "FAILED - Unacceptable move: $lLastMove";
  }

  return "OK";
}

