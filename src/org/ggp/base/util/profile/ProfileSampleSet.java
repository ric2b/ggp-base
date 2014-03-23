
package org.ggp.base.util.profile;

public abstract class ProfileSampleSet
{
  public abstract void beginSection(ProfileSection sample);

  public abstract void endSection(ProfileSection sample);

  public abstract void resetStats();
}
