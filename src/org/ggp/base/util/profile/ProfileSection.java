
package org.ggp.base.util.profile;

public class ProfileSection
{
  private String name;
  private long   startTime;
  private long   elapsedTime;

  public ProfileSection(String name)
  {
    if (ProfilerContext.getContext() != null)
    {
      //System.out.println("Enter section: " + name);
      this.name = name;
      ProfilerContext.getContext().beginSection(this);
      startTime = System.nanoTime();
    }
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
    if (ProfilerContext.getContext() != null)
    {
      elapsedTime = System.nanoTime() - startTime;

      ProfilerContext.getContext().endSection(this);
      //System.out.println("Exit section: " + name);
    }
  }
}
