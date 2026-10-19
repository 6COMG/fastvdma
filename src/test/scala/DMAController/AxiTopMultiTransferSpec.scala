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
  * Regression coverage for two back-to-back transfers to distinct destination
  * addresses across an asymmetric width crossing.
  *
  * The conservative cases arm the next transfer only after the previous one
  * completes; the pending-start case arms the second transfer while the first is
  * still running, which the default depth-1 pending start must queue.
  */
class AxiTopMultiTransferSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXI top wrapper multi-transfer (asymmetric widths)"

  private val cfg = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 512)

  // For the pending-start case the 1 KiB reads must be split into <=256-beat
  // AXI bursts, which needs readMaxBurst set.
  private val cfgBurst = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 512,
                                       readMaxBurst = 256, writeMaxBurst = 16)

  it should "keep two transfers at destination addresses far apart" in {
    test(new DMATop()(cfg)).runPeekPoke(new AxiTopMultiTransferTest(_, 1024)(cfg))
  }

  it should "keep two transfers one destination word apart (no aliasing)" in {
    test(new DMATop()(cfg)).runPeekPoke(new AxiTopMultiTransferTest(_, 64)(cfg))
  }

  it should "keep two transfers across a 4 KiB address boundary" in {
    test(new DMATop()(cfg)).runPeekPoke(new AxiTopMultiTransferTest(_, 4096)(cfg))
  }

  it should "keep two transfers across a 64 KiB address boundary" in {
    test(new DMATop()(cfg)).runPeekPoke(new AxiTopMultiTransferTest(_, 65536)(cfg))
  }

  // The pending-start path: arm transfer 2 without waiting for transfer 1.
  it should "queue a start armed while the previous transfer is still running" in {
    test(new DMATop()(cfgBurst)).runPeekPoke(new AxiTopMultiArmTest(_)(cfgBurst))
  }
}
