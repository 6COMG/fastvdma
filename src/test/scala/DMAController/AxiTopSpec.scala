/*
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

import chiseltest.ChiselScalatestTester
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import DMAController.DMAConfig._

class AxiTopSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXI top wrapper (DMATopAXI_AXIL_AXI)"

  val config64 = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 64, destWidth = 64)
  it should "perform a strided 2D memory-to-memory transfer over AXI4 with 64-bit data" in {
    test(new DMATop()(config64)).runPeekPoke(dut => new AxiTopMemoryTest(dut)(config64))
  }

  val config32 = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 32)
  it should "perform a strided 2D memory-to-memory transfer over AXI4 with 32-bit data" in {
    test(new DMATop()(config32)).runPeekPoke(dut => new AxiTopMemoryTest(dut)(config32))
  }

  val config64Read512Write = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 64, destWidth = 512)
  it should "bridge a 64-bit read bus to a 512-bit write bus" in {
    test(new DMATop()(config64Read512Write)).runPeekPoke(dut => new AxiTopMemoryTest(dut)(config64Read512Write))
  }

  val config512Read64Write = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 512, destWidth = 64)
  it should "bridge a 512-bit read bus to a 64-bit write bus" in {
    test(new DMATop()(config512Read64Write)).runPeekPoke(dut => new AxiTopMemoryTest(dut)(config512Read64Write))
  }
}
