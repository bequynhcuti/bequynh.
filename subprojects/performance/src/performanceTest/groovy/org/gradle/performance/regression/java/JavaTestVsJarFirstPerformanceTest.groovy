/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.performance.regression.java

import org.gradle.performance.AbstractCrossVersionPerformanceTest
import org.gradle.performance.annotations.RunFor
import org.gradle.performance.annotations.Scenario

import static org.gradle.performance.annotations.ScenarioType.PER_DAY
import static org.gradle.performance.results.OperatingSystem.LINUX

class JavaTestVsJarFirstPerformanceTest extends AbstractCrossVersionPerformanceTest {
    def setup() {
        runner.targetVersions = ["7.5-20220206232035+0000"]
    }

    @RunFor([
            @Scenario(type = PER_DAY, operatingSystems = [LINUX], testProjects = ["mediumJavaMultiProjectWithAPIProjectDependencies"])
    ])
    def "test is faster with running jar first"() {
        given:
        runner.warmUpRuns = 5
        runner.runs = 20
        runner.tasksToRun = ['clean', 'test']

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()
    }
}