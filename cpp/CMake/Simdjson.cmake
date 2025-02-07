# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

include_guard(GLOBAL)
include(FetchContent)
function(set_with_default var_name envvar_name default)
  if(DEFINED ENV{${envvar_name}})
    set(${var_name}
        $ENV{${envvar_name}}
        PARENT_SCOPE)
  else()
    set(${var_name}
        ${default}
        PARENT_SCOPE)
  endif()
endfunction()

macro(resolve_dependency_url dependency_name)
  # Prepend prefix for default checksum.
  string(PREPEND GLUTEN_${dependency_name}_BUILD_SHA256_CHECKSUM "SHA256=")

  set_with_default(
    GLUTEN_${dependency_name}_SOURCE_URL GLUTEN_${dependency_name}_URL
    ${GLUTEN_${dependency_name}_SOURCE_URL})
  if(DEFINED ENV{GLUTEN_${dependency_name}_URL})
    set_with_default(GLUTEN_${dependency_name}_BUILD_SHA256_CHECKSUM
                     GLUTEN_${dependency_name}_SHA256 "")
    if(DEFINED ENV{GLUTEN_${dependency_name}_SHA256})
      string(PREPEND GLUTEN_${dependency_name}_BUILD_SHA256_CHECKSUM "SHA256=")
    endif()
  endif()
endmacro()

set(GLUTEN_SIMDJSON_VERSION 3.1.5)
set(GLUTEN_SIMDJSON_BUILD_SHA256_CHECKSUM
    5b916be17343324426fc467a4041a30151e481700d60790acfd89716ecc37076)
set(GLUTEN_SIMDJSON_SOURCE_URL
    "https://github.com/simdjson/simdjson/archive/refs/tags/v${GLUTEN_SIMDJSON_VERSION}.tar.gz"
)

resolve_dependency_url(SIMDJSON)

message(STATUS "Building simdjson from source")

FetchContent_Declare(
  simdjson
  URL ${GLUTEN_SIMDJSON_SOURCE_URL}
  URL_HASH ${GLUTEN_SIMDJSON_BUILD_SHA256_CHECKSUM})

FetchContent_MakeAvailable(simdjson)
target_compile_options(simdjson PUBLIC -fPIC)
