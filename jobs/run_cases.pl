use strict;

use JSON;

chdir('..');

#*****************************************************************************#
#* Create a directory for recording the results.                             *#
#*****************************************************************************#
my $gResultsDir = 'data\\tests\\results\\' . sprintf "%d-%02d-%02d.%02d%02d%02d", map { $$_[5]+1900, $$_[4]+1, $$_[3], $$_[2], $$_[1], $$_[0]} [localtime];
system("md", $gResultsDir);
open(SUMMARY, ">$gResultsDir\\summary.txt") || die "Failed to create results summary: $!\n";

print "Results will be written to $gResultsDir\n";

#*****************************************************************************#
#* Overall results.                                                          *#
#*****************************************************************************#
my $gNumSuites = 0;
my $gNumCases  = 0;
my $gNumPasses = 0;

#*****************************************************************************#
#* Process all the test suites, unless one was provided on the command line. *#
#*****************************************************************************#
my @lSuites;
if (scalar(@ARGV) >= 1)
{
  @lSuites = ($ARGV[0]);
}
else
{
  @lSuites = glob('..\data\tests\suites\*.json');
}

foreach my $lSuiteFile (@lSuites)
{
  $gNumSuites++;

  #***************************************************************************#
  #* Read the test suite.                                                    *#
  #***************************************************************************#
  open(SUITE, $lSuiteFile) or die "Failed to open suite $lSuiteFile: $!\n";
  summarize("Suite: $lSuiteFile\n");
  my $lSuite = do {local $/; decode_json <SUITE>};
  close(SUITE);

  #***************************************************************************#
  #* Run all the cases in the suite.                                         *#
  #***************************************************************************#
  foreach my $lCase (@{$lSuite->{cases}})
  {
    $gNumCases++;
    summarize("  Case $gNumCases: $lCase->{case}...");

    #*************************************************************************#
    #* Make sure there isn't anything left lying around from the last run.   *#
    #*************************************************************************#
    unlink('record.json');

    #*************************************************************************#
    #* Start the players.                                                    *#
    #*************************************************************************#
    my $lRoleIndex = 0;
    foreach my $lPlayer (@{$lCase->{players}})
    {
      $lPlayer->{port} = 9147 + $lRoleIndex;
      my $lPlayerDiags = "$gResultsDir\\$gNumCases.role$lRoleIndex.log";
      my @lSysArgs = ('jobs\player',
                      $lPlayerDiags,
                      $lPlayer->{port},
                      $lPlayer->{type});
      defined($lPlayer->{args}) && push(@lSysArgs, @{$lPlayer->{args}});
      system(@lSysArgs);
      $lRoleIndex++;
    }

    #*************************************************************************#
    #* Give all the players a moment to start.                               *#
    #*************************************************************************#
    sleep(5);

    #*************************************************************************#
    #* Start the server.                                                     *#
    #*************************************************************************#
    my @lSysArgs = ('jobs\server',
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

    #*************************************************************************#
    #* Copy the match record for posterity.                                  *#
    #*************************************************************************#
    my @lRecords = glob('oneshot\*.json');
    my $lSavedRecord = "$gResultsDir\\$gNumCases.json";
    system('copy', $lRecords[0], $lSavedRecord, ">NUL");
    system('copy', 'server.log', "$gResultsDir\\$gNumCases.server.log", ">NUL");

    #*************************************************************************#
    #* Check the result.                                                     *#
    #*************************************************************************#
    my $lResult = checkAcceptable($lCase->{check}->{player},
                                  $lCase->{check}->{acceptable},
                                  $lSavedRecord);
    summarize("$lResult\n");
  }
}

#*****************************************************************************#
#* Print a summary of the results.                                           *#
#*****************************************************************************#
my $lSummary =
    "\nSummary: Passed $gNumPasses / $gNumCases cases in $gNumSuites suites\n";
summarize($lSummary);

#*****************************************************************************#
#* Tidy up.                                                                  *#
#*****************************************************************************#
close(SUMMARY);
exit(0);


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
    return "FAILED - Unacceptable move: $lLastMove, see $xiFilename";
  }

  $gNumPasses++;
  return "OK";
}

sub summarize
{
  my ($lMessage) = @_;

  print $lMessage;
  print SUMMARY $lMessage;
}

