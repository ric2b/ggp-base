
package org.ggp.base.util.profile;

/**
 * Class representing a profiled piece of code.
 */
public class ProfileSection
{
  private static final boolean PROFILING_ENABLED = false;
  private static final ProfileSection DUMMY_PROFILE_SECTION = new ProfileSection("Dummy");

  private final String name;
  private final long   startTime;
  private long         elapsedTime;

  /**
   * Create a new profiled section with the specified name.  Be sure to call exitScope() in the reverse order of
   * creation.
   *
   * @param xiName - the section name.
   * @return a new profiled section.
   */
  public static ProfileSection newInstance(String xiName)
  {
    // If profiling is disabled, simply return a dummy instance.  Otherwise really construct a new object.  We use this
    // level of indirection to prevent massive heap churn when profiling is disabled.
    if ((PROFILING_ENABLED) && (ProfilerContext.getContext() != null))
    {
      return new ProfileSection(xiName);
    }
    return DUMMY_PROFILE_SECTION;
  }

  private ProfileSection(String name)
  {
    this.name = name;
    if (ProfilerContext.getContext() != null)
    {
      ProfilerContext.getContext().beginSection(this);
    }
    startTime = System.nanoTime();
  }

  public String getName()
  {
    return name;
  }

  public long getElapsedTime()
  {
    return elapsedTime;
  }

  public void exitScope()
  {
    if ((PROFILING_ENABLED) && (ProfilerContext.getContext() != null))
    {
      elapsedTime = System.nanoTime() - startTime;
      ProfilerContext.getContext().endSection(this);
    }
  }
}
