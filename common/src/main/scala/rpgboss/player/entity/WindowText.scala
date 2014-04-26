package rpgboss.player.entity

import rpgboss.model._
import rpgboss.model.resource._
import java.awt._
import java.awt.image._
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.BitmapFont.HAlignment
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.assets.AssetManager
import scala.concurrent.Promise
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import rpgboss.player._

object WindowText {
  val colorCtrl = """\\[Cc]\[(\d+)\]""".r
  val nameCtrl = """\\[Nn]\[(\d+)\]""".r
  val variableCtrl = """\\[Vv]\[([a-zA-Z_$][\w_$]*)\]""".r

  def nameReplace(raw: String, nameList: Array[String]) =
    nameCtrl.replaceAllIn(raw, rMatch => nameList(rMatch.group(1).toInt))
  def variableReplace(raw: String, persistent: PersistentState) = {
    variableCtrl.replaceAllIn(raw, rMatch =>
      persistent.getInt(rMatch.group(1)).toString)
  }
}

class WindowText(
  persistent: PersistentState,
  initialText: Array[String],
  x: Int, y: Int, w: Int, h: Int,
  fontbmp: BitmapFont,
  justification: Int = Window.Left,
  var lineHeight: Int = 32) {
  
  def processText(text: Array[String]): Array[String] = {
    val newText = for(line <- text) yield {
      val namesProcessedLine = WindowText.nameReplace(
        line,
        persistent.getStringArray(ScriptInterfaceConstants.CHARACTER_NAMES))
  
      WindowText.variableReplace(namesProcessedLine, persistent)
    }
    
    newText.toArray
  }
  
  protected var _text: Array[String] = processText(initialText)
  
  def setLineHeight(height: Int) =
    lineHeight = height
  
  def updateText(newText: Array[String]) =
    _text = processText(newText)

  def drawLine(b: SpriteBatch, line: String, xOffset: Float, yOffset: Float) = {
    val colorCodesExist = WindowText.colorCtrl.findFirstIn(line).isDefined

    // Make left-aligned if color codes exist
    val fontAlign = if (colorCodesExist) {
      HAlignment.LEFT
    } else {
      justification match {
        case Window.Left => HAlignment.LEFT
        case Window.Center => HAlignment.CENTER
        case Window.Right => HAlignment.RIGHT
      }
    }

    // Draw shadow
    fontbmp.setColor(Color.BLACK)
    fontbmp.drawMultiLine(b, line,
      x + xOffset + 2,
      y + yOffset + 2,
           w, fontAlign)

    fontbmp.setColor(Color.WHITE)

    // Finds first color token, prints everything behind it, sets the color
    // and then does it again with the remaining text.
    @annotation.tailrec
    def printUntilColorTokenOrEnd(remainingText: CharSequence,
                                  xStart: Float): Unit = {
      if (remainingText.length() == 0)
        return

      val rMatchOption = WindowText.colorCtrl.findFirstMatchIn(remainingText)
      val textToPrintNow = rMatchOption.map(_.before).getOrElse(remainingText)

      val textBounds =
        fontbmp.drawMultiLine(b, textToPrintNow,
          xStart,
          y + yOffset,
                   w, fontAlign)

      printUntilColorTokenOrEnd(rMatchOption.map(_.after).getOrElse(""),
                                xStart + textBounds.width)
    }

    printUntilColorTokenOrEnd(line, x + xOffset)
  }

  def update(delta: Float) = {}

  def render(b: SpriteBatch, startLine: Int, linesToDraw: Int): Unit = {
    val endLine = math.min(_text.length, startLine + linesToDraw)
    for (lineI <- startLine until endLine) {
      val offset = lineI - startLine
      drawLine(b, _text(lineI), 0, offset * lineHeight)
    }
  }

  def render(b: SpriteBatch): Unit = {
    render(b, 0, _text.length)
  }
}

class PrintingWindowText(
  persistent: PersistentState,
  initialText: Array[String],
  x: Int, y: Int, w: Int, h: Int,
  skin: Windowskin,
  skinRegion: TextureRegion,
  fontbmp: BitmapFont,
  msPerChar: Int = 50,
  linesPerBlock: Int = 4,
  justification: Int = Window.Left)
  extends WindowText(
    persistent, initialText, x, y, w, h, fontbmp, justification) {
  assume(msPerChar >= 0)

  def drawAwaitingArrow = true

  /**
   * When this is in the next block, the whole block has been printed.
   */
  private var _lineI =
    if (msPerChar == 0) math.min(_text.length, linesPerBlock) else 0

  private var _charI = 0
  private var _blockI = 0
  
  /**
   * Game time since the last character was printed. This is usually left to be
   * non-zero after each frame, since we conceptually print characters between
   * frames.
   */
  private var _timeSinceLastCharacter: Double = 0

  def wholeBlockPrinted = _lineI >= (_blockI + 1) * linesPerBlock
  def allTextPrinted = !(_lineI < _text.length)
  
  def awaitingInput = allTextPrinted || wholeBlockPrinted
  
  def advanceBlock() = {
    _blockI += 1
    _timeSinceLastCharacter = 0
    
    if (msPerChar == 0) {
      _lineI = math.min(_text.length, (_blockI + 1) * linesPerBlock)
    } else {
      assert(_lineI == _blockI * linesPerBlock)
      assert(_charI == 0)
    }
  }

  /**
   * Used to print some text faster if the user is getting impatient.
   */
  def speedThrough() =
    _timeSinceLastCharacter += 1.0
  
  override def updateText(newText: Array[String]) = {
    super.updateText(newText)

    _lineI = 0
    _charI = 0
    _blockI = 0
    _timeSinceLastCharacter = 0
  }

  override def update(delta: Float): Unit = {
    if (awaitingInput)
      return
    
    _timeSinceLastCharacter += delta
    
    val timePerCharacter = msPerChar / 1000.0
    
    // This loop advances at most one line per iteration.
    while (!wholeBlockPrinted && !allTextPrinted && 
           _timeSinceLastCharacter > timePerCharacter) {
      assert(_lineI <= _text.length)
      val line = _text(_lineI)

      val charsLeftInLine = line.length() - _charI
      val charsWeHaveTimeToPrint = 
        (_timeSinceLastCharacter / timePerCharacter).toInt
      val charsAdvanced = math.min(charsLeftInLine, charsWeHaveTimeToPrint)
      
      _charI += charsAdvanced

      if (_charI >= line.length()) {
        _lineI += 1
        _charI = 0
      }

      _timeSinceLastCharacter -= charsAdvanced * timePerCharacter
    }
  }

  override def render(b: SpriteBatch): Unit = {
    // Draw all complete lines in current block
    for (i <- _blockI * linesPerBlock to (_lineI - 1)) {
      val idxInBlock = i % linesPerBlock
      drawLine(b, _text(i), 0, idxInBlock * lineHeight)
    }

    // Draw the currently writing line
    if (_lineI < _text.length) {
      val idxInBlock = _lineI % linesPerBlock
      drawLine(b, _text(_lineI).take(_charI), 0, idxInBlock * lineHeight)
    }

    // If waiting for user input to finish, draw the arrow
    if (awaitingInput) {
      skin.drawArrow(b, skinRegion, x + w / 2 - 8, y + h, 16, 16)
    }
  }
}