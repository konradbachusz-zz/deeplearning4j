/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

/*
 * broadcasting.h
 *
 *  Created on: Dec 28, 2015
 *      Author: agibsonccc
 */

#ifndef BROADCASTING_BOOL_H_
#define BROADCASTING_BOOL_H_
#include <dll.h>
#include <helpers/shape.h>
#include <templatemath.h>
#include <pairwise_util.h>
#include <ops/ops.h>
#include <op_boilerplate.h>
#include <helpers/DebugHelper.h>

#ifdef __CUDACC__
#include <cuda.h>
#include <cuda_runtime.h>
#endif
#ifdef __JNI__
#include <jni.h>
#endif

#include <helpers/TAD.h>

#include "legacy_ops.h"

namespace functions {
    namespace broadcast {

/**
 * Broadcast operation
 * for broadcasting a smaller tensor
 * along long a bigger one.
 */
        template<typename X, typename Z>
        class BroadcastBool {
        public:

#ifdef __CUDACC__

            template<typename OpType>
			static __device__ void transformCuda(
			void *x,
			Nd4jLong *xShapeInfo,
			void *y,
			Nd4jLong *yShapeInfo,
			void *result,
			Nd4jLong *resultShapeInfo,
			int *dimension,
			int dimensionLength, Nd4jLong *tadOnlyShapeInfo, Nd4jLong *tadOffsets, Nd4jLong *tadOnlyShapeInfoZ, Nd4jLong *tadOffsetsZ);

            template <typename OpClass>
            static __host__ void intermediateBroadcast(dim3 launchDims, cudaStream_t *stream, void *x, Nd4jLong *xShapeInfo, void *y, Nd4jLong *yShapeInfo, void *result, Nd4jLong *resultShapeInfo, int *dimension, int dimensionLength, Nd4jLong *tadOnlyShapeInfo, Nd4jLong *tadOffsets, Nd4jLong *tadOnlyShapeInfoZ, Nd4jLong *tadOffsetsZ);

            static __host__ void execBroadcast(dim3 launchDims, cudaStream_t *stream, int opNum, void *x, Nd4jLong *xShapeInfo, void *y, Nd4jLong *yShapeInfo, void *result, Nd4jLong *resultShapeInfo, int *dimension, int dimensionLength, Nd4jLong *tadOnlyShapeInfo, Nd4jLong *tadOffsets, Nd4jLong *tadOnlyShapeInfoZ, Nd4jLong *tadOffsetsZ);

            template<typename OpType>
			static __device__ void transformInverseCuda(
			void *x,
			Nd4jLong *xShapeInfo,
			void *y,
			Nd4jLong *yShapeInfo,
			void *result,
			Nd4jLong *resultShapeInfo,
			int *dimension,
			int dimensionLength, Nd4jLong *tadOnlyShapeInfo, Nd4jLong *tadOffsets, Nd4jLong *tadOnlyShapeInfoZ, Nd4jLong *tadOffsetsZ);

            template <typename OpClass>
            static __host__ void intermediateInverseBroadcast(dim3 launchDims, cudaStream_t *stream, void *x, Nd4jLong *xShapeInfo, void *y, Nd4jLong *yShapeInfo, void *result, Nd4jLong *resultShapeInfo, int *dimension, int dimensionLength, Nd4jLong *tadOnlyShapeInfo, Nd4jLong *tadOffsets, Nd4jLong *tadOnlyShapeInfoZ, Nd4jLong *tadOffsetsZ);

            static __host__ void execInverseBroadcast(dim3 launchDims, cudaStream_t *stream, int opNum, void *x, Nd4jLong *xShapeInfo, void *y, Nd4jLong *yShapeInfo, void *result, Nd4jLong *resultShapeInfo, int *dimension, int dimensionLength, Nd4jLong *tadOnlyShapeInfo, Nd4jLong *tadOffsets, Nd4jLong *tadOnlyShapeInfoZ, Nd4jLong *tadOffsetsZ);

#else

            static void exec(int opNum,
                             void *x,
                             Nd4jLong *xShapeInfo,
                             void *y,
                             Nd4jLong *yShapeInfo,
                             void *result,
                             Nd4jLong *resultShapeInfo,
                             int *dimension,
                             int dimensionLength,
                             Nd4jLong *tadShapeInfo,
                             Nd4jLong *tadOffset,
                             Nd4jLong *tadShapeInfoZ,
                             Nd4jLong *tadOffsetZ,
                             uint64_t start,
                             uint64_t stop);

            static void execInverse(int opNum,
                             void *x,
                             Nd4jLong *xShapeInfo,
                             void *y,
                             Nd4jLong *yShapeInfo,
                             void *result,
                             Nd4jLong *resultShapeInfo,
                             int *dimension,
                             int dimensionLength,
                             Nd4jLong *tadShapeInfo,
                             Nd4jLong *tadOffset,
                             Nd4jLong *tadShapeInfoZ,
                             Nd4jLong *tadOffsetZ,
                             uint64_t start,
                             uint64_t stop);

            /**
             * CPU execution
             * @param x the input
             * @param xShapeInfo the x shape information
             * @param y the y data
             * @param yShapeInfo the y shape information
             * @param result the result
             * @param resultShapeInfo the result shape information
             * @param dimension the dimension to broadcast along long
             * @param dimensionLength the length of the dimension buffer
             */
            template<typename OpType>
            static void exec(void *x,
                             Nd4jLong *xShapeInfo,
                             void *y,
                             Nd4jLong *yShapeInfo,
                             void *result,
                             Nd4jLong *resultShapeInfo,
                             int *dimension,
                             int dimensionLength,
                             Nd4jLong *tadShapeInfo,
                             Nd4jLong *tadOffset,
                             Nd4jLong *tadShapeInfoZ,
                             Nd4jLong *tadOffsetZ,
                             uint64_t start,
                             uint64_t stop);

            template<typename OpType>
            static void execInverse(void *x,
                             Nd4jLong *xShapeInfo,
                             void *y,
                             Nd4jLong *yShapeInfo,
                             void *result,
                             Nd4jLong *resultShapeInfo,
                             int *dimension,
                             int dimensionLength,
                             Nd4jLong *tadShapeInfo,
                             Nd4jLong *tadOffset,
                             Nd4jLong *tadShapeInfoZ,
                             Nd4jLong *tadOffsetZ,
                             uint64_t start,
                             uint64_t stop);
#endif
        };
    }
}

#endif /* BROADCASTING_H_ */
