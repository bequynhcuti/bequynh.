/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.instrumentation.processor.modelreader;

import org.gradle.internal.instrumentation.model.CallInterceptionRequest;

public interface CallInterceptionRequestReader<T> {
    Result readRequest(T input);

    interface Result {
        class Success implements Result {
            private final CallInterceptionRequest request;

            public Success(CallInterceptionRequest request) {
                this.request = request;
            }

            public CallInterceptionRequest getRequest() {
                return request;
            }
        }

        class RequestNotFound implements Result {
        }

        class InvalidRequest implements Result {
            public final String reason;

            public InvalidRequest(String reason) {
                this.reason = reason;
            }
        }
    }
}
