/*
 * Copyright 2013-2024, Seqera Labs
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
package nextflow.cloud.azure.config

import groovy.transform.CompileStatic
import nextflow.cloud.azure.nio.AzFileSystemProvider

/**
 * Model Azure managed identity config options
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@CompileStatic
class AzManagedIdentityOpts {

    String clientId

    AzManagedIdentityOpts(Map config) {
        assert config != null
        this.clientId = config.clientId
    }

    Map<String, Object> getEnv() {
        Map<String, Object> props = new HashMap<>();
        props.put(AzFileSystemProvider.AZURE_MANAGED_IDENTITY, clientId)
        return props
    }

}
