
package org.ggp.base.util.profile;

public class ProfilerContext
{
  private static ProfileSampleSet theContext = null;

  public static void setProfiler(ProfileSampleSet sampleSet)
  {
    theContext = sampleSet;
  }

  public static ProfileSampleSet getContext()
  {
    return theContext;
  }

  public static void resetStats()
  {
    if (theContext != null)
    {
      theContext.resetStats();
    }
  }
}
