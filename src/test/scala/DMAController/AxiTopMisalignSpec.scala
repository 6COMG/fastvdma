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
  * Regression coverage for the `Start misaligned` status bit. Alignment is per
  * side (each frontend's own data-bus width), so a 4-byte-aligned 32-bit CPU
  * source is valid while the 512-bit island side needs 64-byte alignment.
  */
class AxiTopMisalignSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXI top wrapper start alignment diagnostics"

  private val cfg = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 512)

  it should "flag a misaligned start and clear on Control write" in {
    test(new DMATop()(cfg)).runPeekPoke(new AxiTopMisalignTest(_)(cfg))
  }
}
