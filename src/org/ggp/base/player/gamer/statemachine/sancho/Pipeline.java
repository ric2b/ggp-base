package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * Highly efficient, lock-free rollout request pipeline.
 */
public class Pipeline
{
  /**
   * Individual per-rollout-thread pipelines.
   */
  final private SimplePipeline[] mThreadPipelines;

  /**
   * The next rollout thread to give new (expansion) work to and the next thread to drain (back-propagation) work from.
   */
  private int mNextExpandThread = -1;
  private int mNextDrainThread = -1;

  /**
   * The maximum permitted number of items in the pipeline.
   */
  private final int mMaxQueuedItems;

  /**
   * The current number of items in the pipeline.
   */
  private int mCurrentQueuedItems;

  /**
   * Create a pipeline with the specified maximum size.
   *
   * @param xiSize - the maximum number of objects that can be in the pipeline.
   * @param xiNumRoles number of roles in the game
   */
  public Pipeline(int xiSize, int xiNumRoles)
  {
    mMaxQueuedItems = xiSize;
    mThreadPipelines = new SimplePipeline[ThreadControl.ROLLOUT_THREADS];

    // Create per-thread pipelines big enough that we'll be able to queue xiSize items across all of them.  If there's
    // a little spare capacity in the per-thread queues, that's okay.  We still limit the overall pipeline size.
    int lPerThreadSize = (xiSize + ThreadControl.ROLLOUT_THREADS - 1) / ThreadControl.ROLLOUT_THREADS;

    // Per-thread pipeline size must be a power of 2.
    lPerThreadSize = Integer.highestOneBit(lPerThreadSize - 1) * 2;
    System.out.println("Per-rollout-thread pipeline size = " + lPerThreadSize);

    for (int lii = 0; lii < ThreadControl.ROLLOUT_THREADS; lii++)
    {
      mThreadPipelines[lii] = new SimplePipeline(lPerThreadSize, xiNumRoles);
    }
  }

  /**
   * @return whether the tree thread can perform an expansion now.
   */
  public boolean canExpand()
  {
    return mCurrentQueuedItems < mMaxQueuedItems;
  }

  /**
   * @return a blank rollout request to be filled in.
   *
   * The caller must ensure (or know) that {@link #canExpand()} is true before calling this method.  After calling
   * this method, the caller must call {@link #expandComplete()}.
   */
  public RolloutRequest getNextExpandSlot()
  {
    // For the purposes of tracking the number of outstanding items, this counts as one.  (Doing this here, rather than
    // in expandComplete(), prevents the search thread from taking more slots than it's meant to - with the intention of
    // publishing them all later.  This isn't a model that we currently use, but it isn't forbidden - so safer to get it
    // right in this respect.)
    assert(mCurrentQueuedItems < mMaxQueuedItems) : "Pipeline unexpectedly full - num items: " + mCurrentQueuedItems;
    mCurrentQueuedItems++;

    // Find the next thread with a slot.  The calling restrictions ensure that there is one (and therefore this won't
    // loop forever).
    for (mNextExpandThread = (mNextExpandThread + 1) % ThreadControl.ROLLOUT_THREADS;
        !mThreadPipelines[mNextExpandThread].canExpand();
        mNextExpandThread = (mNextExpandThread + 1) % ThreadControl.ROLLOUT_THREADS)
    {
      // Do nothing.
    }

    // Return the rollout request to be filled in.
    return mThreadPipelines[mNextExpandThread].getNextExpandSlot();
  }

  /**
   * Publish a rollout request for further processing.
   */
  public void completedExpansion()
  {
    mThreadPipelines[mNextExpandThread].expandComplete();
  }

  /**
   * Get the next rollout request for the specified thread.
   *
   * This method will not return until a rollout request is available.
   *
   * @param xiThreadIndex - the thread making the request.
   *
   * @return the next rollout request.
   */
  public RolloutRequest getNextRolloutRequest(int xiThreadIndex)
  {
    return mThreadPipelines[xiThreadIndex].getNextRolloutRequest();
  }

  /**
   * Mark a rollout as complete.
   *
   * @param xiThreadIndex - the thread making the request.
   */
  public void completedRollout(int xiThreadIndex)
  {
    mThreadPipelines[xiThreadIndex].rolloutComplete();
  }

  /**
   * @return whether there are any completed rollout requests ready for back-propagation.
   */
  public boolean canBackPropagate()
  {
    for (int i = 0; i < ThreadControl.ROLLOUT_THREADS; i++)
    {
      if (mThreadPipelines[i].canBackPropagate())
      {
        return true;
      }
    }

    return false;
  }

  /**
   * @return the next rollout request to be back-propagated.
   *
   * This method will not return until a rollout request is available.
   */
  public RolloutRequest getNextRequestForBackPropagation()
  {
    // Find the next thread that has some work to do.  Start with thread 1 (unless there only is one thread) so that we
    // don't yield until we've looked at all threads once.  Always starting at a fixed thread would be in danger of
    // starving some threads - but we always completely drain the pipeline of back-propagation work (whenever we do any
    // such work) so that isn't a problem.
    for (mNextDrainThread = (ThreadControl.ROLLOUT_THREADS == 1 ? 0 : 1);
         !mThreadPipelines[mNextDrainThread].canBackPropagate();
         mNextDrainThread = (mNextDrainThread + 1) % ThreadControl.ROLLOUT_THREADS)
    {
      if (mNextDrainThread == 0)
      {
        // Don't completely busy-wait.  At least yield once per time round all the threads.
        Thread.yield();
      }
    }

    return mThreadPipelines[mNextDrainThread].getNextRequestForBackPropagation();
  }

  /**
   * Mark that back-propagation of a request is complete.
   */
  public void completedBackPropagation()
  {
    mThreadPipelines[mNextDrainThread].backPropagationComplete();
    assert(mCurrentQueuedItems > 0) : "Pipeline unexpectedly empty - num items: " + mCurrentQueuedItems;
    mCurrentQueuedItems--;
  }
}
