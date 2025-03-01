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
package com.squareup.workflow.testing

import com.squareup.workflow.Worker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * A mock implementation of [Worker] for use in tests with `testRender` and [TestRenderResult].
 *
 * Note this [Worker] can not actually emit any output itself. Use
 * [TestRenderResult.handleOutput] or
 * [TestRenderResult.handleFinish] to evaluate output handlers.
 *
 * @see com.squareup.workflow.StatefulWorkflow.testRender
 * @see com.squareup.workflow.StatelessWorkflow.testRender
 */
class MockWorker<T>(val name: String) : Worker<T> {

  @UseExperimental(ExperimentalCoroutinesApi::class)
  override fun run(): Flow<T> {
    throw AssertionError("MockWorker can't do work. Use TestRenderResult.executeWorkerAction*.")
  }

  override fun doesSameWorkAs(otherWorker: Worker<*>): Boolean =
    otherWorker is MockWorker && name == otherWorker.name
}
