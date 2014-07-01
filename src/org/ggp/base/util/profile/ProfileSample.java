
package org.ggp.base.util.profile;

public class ProfileSample
{
  private String               name;
  private long                 accrued;
  private long                 outerAccrued;
  private long                 childTime;
  private int                  count;
  private ThreadLocal<Integer> depth;

  final long                   millSecondInNanoSeconds = 1000000;

  public ProfileSample(String name)
  {
    this.name = name;
    accrued = 0;
    outerAccrued = 0;
    count = 0;
    depth = new ThreadLocal<>();
    depth.set(0);
  }

  public String getName()
  {
    return name;
  }

  public long getAccruedTime()
  {
    return accrued;
  }

  public int getDepth()
  {
    return depth.get();
  }

  public void enterInstance()
  {
    count++;
    depth.set(depth.get().intValue() + 1);
  }

  public void exitInstance(long interval)
  {
    int newDepth = depth.get().intValue() - 1;

    depth.set(newDepth);

    accrued += interval;
    if (newDepth == 0)
    {
      outerAccrued += interval;
    }
  }

  public void accrueChildTime(long interval)
  {
    childTime += interval;
  }

  private String displayInMilliseconds(long value)
  {
    double valueInMilliseconds = value / millSecondInNanoSeconds;

    return String.format("%3.3f ms", valueInMilliseconds);
  }

  @Override
  public String toString()
  {
    return name + "\t" + String.valueOf(count) + "\t" +
           displayInMilliseconds(outerAccrued) + "\t" +
           displayInMilliseconds(accrued - childTime);
  }
}
