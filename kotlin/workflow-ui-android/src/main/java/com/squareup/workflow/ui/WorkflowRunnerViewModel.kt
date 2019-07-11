/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow.ui

import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.squareup.workflow.RenderingAndSnapshot
import com.squareup.workflow.Snapshot
import com.squareup.workflow.Workflow
import com.squareup.workflow.launchWorkflowIn
import io.reactivex.Flowable
import io.reactivex.Observable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.asObservable
import org.jetbrains.annotations.TestOnly
import org.reactivestreams.Publisher
import java.util.concurrent.CancellationException
import kotlin.reflect.jvm.jvmName

/**
 * @param renderingsFlow this is a [Flow] rather than a [LiveData] so that we can easily
 * source both snapshots and renderings from it. This leaves a window during
 * which the first few of these may be missed, since we won't collect from the flow until
 * after the workflow has started -- no big deal, we only care about closing that gap for outputs.
 */
@UseExperimental(ExperimentalCoroutinesApi::class)
@ExperimentalWorkflowUi
internal class WorkflowRunnerViewModel<OutputT : Any>(
  override val viewRegistry: ViewRegistry,
  private val renderingsFlow: Flow<RenderingAndSnapshot<Any>>,
  private val outputFlow: Flow<OutputT>,
  private val scope: CoroutineScope
) : ViewModel(), WorkflowRunner<OutputT> {

  internal class Factory<InputT, OutputT : Any>(
    private val workflow: Workflow<InputT, OutputT, Any>,
    private val viewRegistry: ViewRegistry,
    private val inputs: Flow<InputT>,
    savedInstanceState: Bundle?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
  ) : ViewModelProvider.Factory {
    private val snapshot = savedInstanceState
        ?.getParcelable<PickledWorkflow>(BUNDLE_KEY)
        ?.snapshot

    override fun <T : ViewModel> create(modelClass: Class<T>): T = launchWorkflowIn(
        CoroutineScope(dispatcher),
        workflow,
        inputs,
        snapshot
    ) { renderings, outputs ->
      @Suppress("UNCHECKED_CAST")
      WorkflowRunnerViewModel(viewRegistry, renderings, outputs, this) as T
    }
  }

  override val output: Flowable<out OutputT>
    get() = outputFlow.asFlowable()

  override val renderings: Observable<out Any> get() = renderingsFlow
      .map { it.rendering }
      .asObservable()

  private val snapshotJob = scope.launch {
    renderingsFlow
        .map { it.snapshot }
        .collect { lastSnapshot = it }
  }

  private var lastSnapshot: Snapshot = Snapshot.EMPTY

  override fun onCleared() {
    val cancellationException = CancellationException("WorkflowRunnerViewModel cleared.")
    snapshotJob.cancel(cancellationException)
    scope.cancel(cancellationException)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putParcelable(BUNDLE_KEY, PickledWorkflow(lastSnapshot))
  }

  @TestOnly
  internal fun clearForTest() = onCleared()

  @TestOnly
  internal fun getLastSnapshotForTest() = lastSnapshot

  private companion object {
    val BUNDLE_KEY = WorkflowRunner::class.jvmName + "-workflow"
  }
}
