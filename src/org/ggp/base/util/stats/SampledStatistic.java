package org.ggp.base.util.stats;

/**
 * Keep track of a statistic based on a number of samples.
 *
 * Optimised for recording samples more often than getting the mean and/or standard deviation.
 */
public class SampledStatistic
{
  private int    mNumSamples          = 0;
  private long   mSum                 = 0;
  private long   mSumOfSquares        = 0;

  /**
   * Record a sample.
   *
   * @param xiValue - the sample value.
   */
  public void sample(int xiValue)
  {
    mSum += xiValue;
    mSumOfSquares += (xiValue * xiValue);
    mNumSamples++;
  }

  /**
   * @return the number of samples recorded.
   */
  public int getNumSamples()
  {
    return mNumSamples;
  }

  /**
   * @return the total value of the recorded samples.
   */
  public long getTotal()
  {
    return mSum;
  }

  /**
   * @return the mean value of the recorded samples.
   */
  public double getMean()
  {
    return (double)mSum / (double)mNumSamples;
  }

  /**
   * @return the standard deviation of the recorded samples.
   */
  public double getStdDev()
  {
    if (mNumSamples < 2)
    {
      return 0;
    }

    double lStdDev = Math.sqrt((double)((mNumSamples * mSumOfSquares) - (mSum * mSum)) /
                               (double)(mNumSamples * (mNumSamples - 1)));
    assert(!Double.isNaN(lStdDev));
    assert(!Double.isInfinite(lStdDev));
    assert(lStdDev != 0);

    return lStdDev;
  }
}
