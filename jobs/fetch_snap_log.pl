use strict;

use JSON;
use LWP::UserAgent;

my $API_USER_VAR = 'SNAP_CI_USER';
my $API_KEY_VAR = 'SNAP_CI_API_KEY';

if (not exists $ENV{$API_USER_VAR})
{
  print "Please set environment variable $API_USER_VAR to your snap-ci username\n";
  exit 1;
}
my $lUsername = $ENV{$API_USER_VAR};

if (not exists $ENV{$API_KEY_VAR})
{
  print "Please set environment variable $API_KEY_VAR to your API key from\n" .
        "https://snap-ci.com/settings/api_key.\n";
  exit 1;
}
my $lPassword = $ENV{$API_KEY_VAR};

if (scalar(@ARGV) != 3)
{
  print "Syntax: fetch_snap_log.pl <BuildNum> <Stage> <Artifact>\n";
  exit 1;
}
my $lBuild = $ARGV[0];
my $lStage = $ARGV[1];
my $lArtifact = $ARGV[2];

# Create an HTTP user agent.  Don't allow redirections because we need to
# remove authorization credentials before sending the redirected request.
my $lAgent = LWP::UserAgent->new;
$lAgent->max_redirect(0);

# Dump headers.
#$lAgent->add_handler("request_send",  sub { shift->dump; return });
#$lAgent->add_handler("response_done", sub { shift->dump; return });

# Set up the request.  Supply authorization by default because the server
# doesn't ask for it correctly.
my $lURL = "https://api.snap-ci.com/project/SanchoGGP/ggp-base/branch/develop/artifacts/tracking-pipeline/$lBuild/$lStage/1/$lArtifact";
my $lRequest = HTTP::Request->new(GET => $lURL);
$lRequest->authorization_basic($lUsername, $lPassword);

# Send the request
my $lResponse = $lAgent->request($lRequest);

# Parse the response
if ($lResponse->is_redirect)
{
  # We expect to be redirected.  Create a new request, without the
  # authorization header (otherwise the Amazon server will reject the request).
  $lRequest = HTTP::Request->new(GET => $lResponse->header('Location'));
  $lAgent->max_redirect(7);
  $lResponse = $lAgent->request($lRequest);

  if ($lResponse->is_success)
  {
    # Save off the attachment.
    my $lFilename = "$ENV{TMP}/$lArtifact.tgz";
    open(ATTACHMENT, ">$lFilename") || die "Failed to open $lFilename: $!\n";
    binmode ATTACHMENT;
    print ATTACHMENT $lResponse->content;
    close(ATTACHMENT);
  }
  else
  {
    print $lResponse->status_line . "\n";
    print $lResponse->decoded_content;
  }
}
else
{
  print $lResponse->status_line . "\n";
  print $lResponse->decoded_content;
}
