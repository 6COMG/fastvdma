/*
Copyright (C) 2019-2025 Antmicro
Copyright (C) 2026 6COM

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

SPDX-License-Identifier: Apache-2.0
*/

package DMAController

import circt.stage.ChiselStage
import DMAUtils.DMALogger
import DMAController.DMAConfig._

object DMADriver extends App {
  DMALogger.info("Using the default parameters and AXI_AXIL_AXI configuration.")
  DMALogger.info("Edit DMAConfig.scala to change the configuration.")

  implicit val dmaConfig: DMAConfig = new DMAConfig()
  ChiselStage.emitSystemVerilogFile(new DMATop)
}
