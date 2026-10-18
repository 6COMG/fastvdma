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

package DMAController.Bfm

import DMAController.Bus._
import chisel3.Bits

import scala.collection.mutable.ListBuffer

class AxiStreamMasterBfm(val axi: AXIStream,
                        val packetLen: Int,
                        val peek: Bits => BigInt,
                        val poke: (Bits, BigInt) => Unit,
                        val println: String => Unit) 
extends AxiStreamBfm {

  // Number of bytes carried by a single stream beat.
  private val dataBytes: Int = axi.dataWidth / 8

  private var txList: ListBuffer[BigInt] = new ListBuffer()

  private object State extends Enumeration {
    type State = Value
    val Idle, WriteData= Value
  }

  private var state = State.Idle

  private var wordCnt: Int = 0

  private var tready: BigInt = 0

  def loadFromFile(filename: String): Unit = {
    val words = WordPacking.loadWords(filename, dataBytes)
    for (i <- words.indices) {
      txList += words(i)
    }
    println("AXI Stream BFM, file %s, %d words".format(filename, words.length))
  }

  def saveToFile(filename: String): Unit = {
  }

  private def peekInputs(): Unit = {
    tready = peek(axi.tready)
  }

  private def putData(): Unit = {
    poke(axi.tdata, txList.remove(0))
  }

  private def updateTlast(): Unit = {
    if(wordCnt == packetLen - 1) {
      poke(axi.tlast, 1)
    } else {
      poke(axi.tlast, 0)
    }
  }

  def update(t: Long): Unit = {
    state match {
      case State.Idle => {
        if(txList.nonEmpty) {
          poke(axi.tvalid, 1)
          state = State.WriteData
          putData()
          updateTlast()
        }
      }
      case State.WriteData => {
        if(tready != 0) {
          if(txList.nonEmpty) {
            putData()
            updateTlast()
            if(wordCnt == packetLen) {
              wordCnt = 0
            } else {
              wordCnt += 1
            }
          } else {
            poke(axi.tvalid, 0)
            state = State.Idle
          }
        }
      }
    }
    peekInputs()
  }
}
