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

/**
  * Verifies that a single bidirectional `DMATop` can move data in both
  * directions through the same fixed physical ports (32-bit read / 512-bit
  * write), selected at runtime by the `Direction` register.
  */
class AxiTopDirectionSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "bidirectional AXI top"

  val cfg = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 512)

  it should "move 32-bit -> 512-bit when direction = 0" in {
    test(new DMATop()(cfg)).runPeekPoke(dut => new AxiTopDirectionTest(dut, reverse = false)(cfg))
  }

  it should "move 512-bit -> 32-bit when direction = 1" in {
    test(new DMATop()(cfg)).runPeekPoke(dut => new AxiTopDirectionTest(dut, reverse = true)(cfg))
  }

  // Asymmetric widths with burst splitting enabled: the reverse datapath reads
  // 512-bit words off the write bus and writes 64-bit words onto the read bus,
  // so both the source (64 words/line, writeMaxBurst 16) and the destination
  // (512 words/line, readMaxBurst 256) split into multiple AXI bursts.
  val cfgSplit = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 64, destWidth = 512,
                               readMaxBurst = 256, writeMaxBurst = 16)

  it should "move 512-bit -> 64-bit reverse with split bursts (direction = 1)" in {
    test(new DMATop()(cfgSplit)).runPeekPoke(
      dut => new AxiTopDirectionTest(dut, reverse = true, lineBytesOverride = 4096, lineCountOverride = 4)(cfgSplit)
    )
  }

  it should "move 64-bit -> 512-bit forward with split bursts (direction = 0)" in {
    test(new DMATop()(cfgSplit)).runPeekPoke(
      dut => new AxiTopDirectionTest(dut, reverse = false, lineBytesOverride = 4096, lineCountOverride = 4)(cfgSplit)
    )
  }
}
