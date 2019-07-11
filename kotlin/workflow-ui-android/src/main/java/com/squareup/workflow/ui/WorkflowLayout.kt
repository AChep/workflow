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

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import android.util.AttributeSet
import android.util.SparseArray
import android.view.View
import android.widget.FrameLayout
import com.squareup.coordinators.Coordinator
import com.squareup.coordinators.Coordinators
import io.reactivex.Observable
import io.reactivex.Observable.never
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables

/**
 * A view that can be driven by a [WorkflowRunner]. In most cases you'll use
 * [Activity.setContentWorkflow][com.squareup.workflow.ui.setContentWorkflow]
 * or subclass [WorkflowFragment] rather than manage this class directly.
 */
@ExperimentalWorkflowUi
class WorkflowLayout(
  context: Context,
  attributeSet: AttributeSet? = null
) : FrameLayout(context, attributeSet), HandlesBack {
  private var restoredChildState: SparseArray<Parcelable>? = null
  private val showing: View? get() = if (childCount > 0) getChildAt(0) else null

  private val attached: Observable<Boolean> = Observable
      .create<Boolean> { emitter ->
        emitter.onNext(isAttachedToWindow)

        object : OnAttachStateChangeListener {
          override fun onViewDetachedFromWindow(view: View) {
            emitter.onNext(false)
          }

          override fun onViewAttachedToWindow(view: View) {
            emitter.onNext(true)
          }
        }.apply {
          addOnAttachStateChangeListener(this)
          emitter.setCancellable { removeOnAttachStateChangeListener(this) }
        }
      }

  private var renderingsSubscription = Disposables.disposed()

  /**
   * Subscribes to [renderings] (only while [isAttachedToWindow]), and uses [registry] to
   * [build a new view][ViewRegistry.buildView] each time a new type of rendering is received,
   * making that view the only child of this one.
   *
   * Views created this way may make recursive calls to [ViewRegistry.buildView] to make
   * children of their own to handle nested renderings.
   */

  fun start(
    renderings: Observable<out Any>,
    registry: ViewRegistry
  ) {
    renderingsSubscription.dispose()
    renderingsSubscription = attached
        .switchMap { attached ->
          when {
            attached -> renderings
            else -> never()
          }
        }
        .subscribe { show(it, registry) }
  }

  /**
   * Convenience override to start this layout from [renderings][WorkflowRunner.renderings]
   * and [viewRegistry][WorkflowRunner.viewRegistry] of [workflowRunner].
   */
  fun start(workflowRunner: WorkflowRunner<*>) =
    start(workflowRunner.renderings, workflowRunner.viewRegistry)

  override fun onBackPressed(): Boolean {
    return showing
        ?.let { HandlesBack.Helper.onBackPressed(it) }
        ?: false
  }

  private fun show(
    newRendering: Any,
    registry: ViewRegistry
  ) {
    showing?.takeIf { it.canShowRendering(newRendering) }
        ?.let { it ->
          it.showRendering(newRendering)
          return
        }

    removeAllViews()
    val newView = registry.buildView(newRendering, this)
    restoredChildState?.let { restoredState ->
      restoredChildState = null
      newView.restoreHierarchyState(restoredState)
    }
    addView(newView)
  }

  override fun onSaveInstanceState(): Parcelable? {
    return SavedState(
        super.onSaveInstanceState()!!,
        SparseArray<Parcelable>().also { array -> showing?.saveHierarchyState(array) }
    )
  }

  override fun onRestoreInstanceState(state: Parcelable?) {
    (state as? SavedState)
        ?.let {
          restoredChildState = it.childState
          super.onRestoreInstanceState(state.superState)
        }
        ?: super.onRestoreInstanceState(state)
  }

  private class SavedState : BaseSavedState {
    constructor(
      superState: Parcelable?,
      childState: SparseArray<Parcelable>
    ) : super(superState) {
      this.childState = childState
    }

    constructor(source: Parcel) : super(source) {
      this.childState = source.readSparseArray<Parcelable>(SavedState::class.java.classLoader)!!
    }

    val childState: SparseArray<Parcelable>

    override fun writeToParcel(
      out: Parcel,
      flags: Int
    ) {
      super.writeToParcel(out, flags)
      @Suppress("UNCHECKED_CAST")
      out.writeSparseArray(childState as SparseArray<Any>)
    }

    companion object CREATOR : Creator<SavedState> {
      override fun createFromParcel(source: Parcel): SavedState =
        SavedState(source)

      override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
    }
  }

  /**
   * Subscribes [update] to [source] only while this [View] is attached to a window.
   */
  private fun <S : Any> View.takeWhileAttached(
    source: Observable<S>,
    update: (S) -> Unit
  ) {
    Coordinators.bind(this) {
      object : Coordinator() {
        var sub: Disposable? = null

        override fun attach(view: View) {
          sub = source.subscribe { screen -> update(screen) }
        }

        override fun detach(view: View) {
          sub?.let {
            it.dispose()
            sub = null
          }
        }
      }
    }
  }
}
