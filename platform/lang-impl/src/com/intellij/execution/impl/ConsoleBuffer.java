/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.execution.impl;

import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.HashSet;
import gnu.trove.TIntArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * IJ user may want the console to use cyclic buffer, i.e. don't keep more than particular amount of symbols. So, we need
 * to have a data structure that allow to achieve that. This class serves for that purpose.
 * <p/>
 * Not thread-safe.
 * <p/>
 * <b>Note:</b> basically this class consists of functionality that is cut from {@link ConsoleViewImpl} in order to make it possible
 * to cover it by tests.
 *
 * @author Denis Zhdanov
 * @since 4/5/11 5:26 PM
 */
public class ConsoleBuffer {
  private static final int DEFAULT_CYCLIC_BUFFER_UNIT_SIZE = 256;

  private static final boolean DEBUG_PROCESSING = false;

  /**
   * Buffer for deferred stdout, stderr and stdin output.
   * <p/>
   * Feel free to check rationale for using this approach at {@link #myCyclicBufferSize} contract.
   */
  final Deque<StringBuilder> myDeferredOutput = new ArrayDeque<>();
  private final Set<ConsoleViewContentType> myContentTypesToNotStripOnCycling = new HashSet<>();

  /**
   * Main console usage scenario assumes the following:
   * <pre>
   * <ul>
   *   <li>
   *      console may be {@link ConsoleViewImpl#print(String, ConsoleViewContentType) provided} with the new text from any thread
   *      (e.g. separate thread is charged for reading output of java application launched under IJ. That output is provided
   *      to the console);
   *   </li>
   *   <li>current class flushes provided text to {@link Editor editor} used for representing it to end-user from EDT;</li>
   *   <li>
   *      dedicated buffer is kept to hold console text between the moment when it's provided to the current class
   *      and flush to the editor;</li>
   * </ul>
   * </pre>
   * <p/>
   * It's also possible to configure console to use cyclic buffer in order to avoid unnecessary memory consumption.
   * However, that implies possibility of the following situation - console user provides it with the great number
   * of small chunks of text (that is the case for junit processing). It's inappropriate to use single {@link StringBuilder} as
   * a buffer then because every time we see that cyclic buffer size is exceeded and we need to cut exceeding text from buffer
   * start, trailing part is moved to the zero offset. That produces extensive CPU usage in case of great number of small messages
   * where every such message exceeds cyclic buffer size.
   * <p/>
   * That is the reason why we use data structure similar to STL deque here - we hold number of string buffers of small size instead
   * of the single big buffer. That means that every 'cut at the start' operation requires much less number of trailing symbols
   * to be moved. Current constant defines default size of that small buffers.
   */

  private final int myCyclicBufferSize;
  private final int myCyclicBufferUnitSize;
  private final boolean myUseCyclicBuffer;

  /**
   * Holds information about number of symbols stored at {@link #myDeferredOutput} collection.
   */
  private int myDeferredOutputLength;

  /**
   * Buffer for deferred stdin output.
   * <p/>
   * Is assumed to store user input data until it's delivered to the target process. That activity is driven from outside this class.
   */
  private StringBuffer myDeferredUserInput = new StringBuffer();

  /**
   * Holds information about lexical division by offsets of the text that is not yet pushed to document.
   * <p/>
   * Target offsets are anchored to the {@link #myDeferredOutput deferred buffer}.
   */
  private final List<ConsoleViewImpl.TokenInfo> myTokens = new ArrayList<>();

  private boolean myKeepSlashR = true;

  ConsoleBuffer() {
    this(useCycleBuffer(), getCycleBufferSize(), DEFAULT_CYCLIC_BUFFER_UNIT_SIZE);
  }

  ConsoleBuffer(boolean useCyclicBuffer, int cyclicBufferSize, int cyclicBufferUnitSize) {
    myUseCyclicBuffer = useCyclicBuffer;
    myCyclicBufferSize = Math.max(cyclicBufferSize, 0);
    myCyclicBufferUnitSize = cyclicBufferUnitSize;
    myContentTypesToNotStripOnCycling.add(ConsoleViewContentType.USER_INPUT);
  }

  public static boolean useCycleBuffer() {
    return !"disabled".equalsIgnoreCase(System.getProperty("idea.cycle.buffer.size"));
  }

  public static int getCycleBufferSize() {
    if (UISettings.getInstance().OVERRIDE_CONSOLE_CYCLE_BUFFER_SIZE) {
      return UISettings.getInstance().CONSOLE_CYCLE_BUFFER_SIZE_KB * 1024;
    }
    return getLegacyCycleBufferSize();
  }

  public static int getLegacyCycleBufferSize() {
    return SystemProperties.getIntProperty("idea.cycle.buffer.size", 1024) * 1024;
  }

  boolean isUseCyclicBuffer() {
    return myUseCyclicBuffer;
  }

  int getCyclicBufferSize() {
    return myCyclicBufferSize;
  }

  void setKeepSlashR(boolean keep) {
    myKeepSlashR = keep;
  }

  int getLength() {
    return myDeferredOutputLength;
  }

  int getUserInputLength() {
    return myDeferredUserInput.length();
  }

  @NotNull
  List<ConsoleViewImpl.TokenInfo> getTokens() {
    return myTokens;
  }

  @NotNull
  String getText() {
    if (myDeferredOutput.size() > 1) {
      final StringBuilder buffer = new StringBuilder();
      for (StringBuilder builder : myDeferredOutput) {
        buffer.append(builder);
      }
      return buffer.toString();
    }
    if (myDeferredOutput.size() == 1) {
      return myDeferredOutput.getFirst().substring(0);
    }
    return "";
  }

  /**
   * This buffer automatically strips text that exceeds {@link #getCycleBufferSize() cyclic buffer size}. However, we may want
   * to avoid 'significant text' stripping, i.e. don't strip the text of particular type.
   * <p/>
   * {@link ConsoleViewContentType#USER_INPUT} is considered to be such a type by default, however, it's possible to overwrite that
   * via the current method.
   *
   * @param types content types that should not be stripped during the buffer's cycling
   */
  void setContentTypesToNotStripOnCycling(@NotNull Collection<ConsoleViewContentType> types) {
    myContentTypesToNotStripOnCycling.clear();
    myContentTypesToNotStripOnCycling.addAll(types);
  }

  void clear() {
    clear(true);
  }
  
  void clear(boolean clearUserInputAsWell) {
    if (myUseCyclicBuffer) {
      myDeferredOutput.clear();
      myDeferredOutput.add(new StringBuilder(myCyclicBufferUnitSize));
    }
    else {
      for (StringBuilder builder : myDeferredOutput) {
        builder.setLength(0);
      }
    }
    myDeferredOutputLength = 0;
    myTokens.clear();
    if (clearUserInputAsWell) {
      myDeferredUserInput = new StringBuffer();
    }
  }

  @Nullable
  String cutFirstUserInputLine() {
    final String text = myDeferredUserInput.substring(0, myDeferredUserInput.length());
    final int index = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));
    if (index < 0) {
      return null;
    }
    myDeferredUserInput.setLength(0);
    myDeferredUserInput.append(text.substring(index + 1));
    return text.substring(0, index + 1);
  }

  void addUserText(int offset, @NotNull String text) {
    myDeferredUserInput.insert(offset, text);
  }

  void removeUserText(int startOffset, int endOffset) {
    if (startOffset >= myDeferredUserInput.length()) {
      return;
    }
    int startToUse = Math.max(0, startOffset);
    int endToUse = Math.min(myDeferredUserInput.length(), endOffset);
    myDeferredUserInput.delete(startToUse, endToUse);
  }

  void replaceUserText(int startOffset, int endOffset, @NotNull String text) {
    myDeferredUserInput.replace(startOffset, endOffset, text);
  }

  /**
   * Asks current buffer to store given text of the given type.
   *
   * @param text           text to store
   * @param contentType type of the given text
   * @param info        hyperlink info for the given text (if any)
   * @return text that is actually stored (there is a possible case that the buffer is full and given text's type
   *         is considered to have lower priority than the stored one, hence, it's better to drop given text completely
   *         or partially) and number of existed symbols removed during storing the given data
   */
  @NotNull
  Pair<String, Integer> print(@NotNull String text, @NotNull ConsoleViewContentType contentType, @Nullable HyperlinkInfo info) {
    int numberOfSymbolsToProceed = text.length();
    int trimmedSymbolsNumber = myDeferredOutputLength;
    if (contentType != ConsoleViewContentType.USER_INPUT) {
      numberOfSymbolsToProceed = trimDeferredOutputIfNecessary(text.length());
      trimmedSymbolsNumber -= myDeferredOutputLength;
    }
    else {
      trimmedSymbolsNumber = 0;
    }

    if (numberOfSymbolsToProceed <= 0) {
      return new Pair<>("", 0);
    }

    if (numberOfSymbolsToProceed < text.length()) {
      text = text.substring(text.length() - numberOfSymbolsToProceed);
    }

    text = StringUtil.convertLineSeparators(text, myKeepSlashR);

    myDeferredOutputLength += text.length();
    StringBuilder bufferToUse;
    if (myDeferredOutput.isEmpty()) {
      myDeferredOutput.add(bufferToUse = new StringBuilder(myCyclicBufferUnitSize));
    }
    else {
      bufferToUse = myDeferredOutput.getLast();
    }
    int offset = 0;
    while (offset < text.length()) {
      if (bufferToUse.length() >= myCyclicBufferUnitSize) {
        myDeferredOutput.add(bufferToUse = new StringBuilder(myCyclicBufferUnitSize));
      }

      if (bufferToUse.length() < myCyclicBufferUnitSize) {
        int numberOfSymbolsToAdd = Math.min(myCyclicBufferUnitSize - bufferToUse.length(), text.length() - offset);
        bufferToUse.append(text.substring(offset, offset + numberOfSymbolsToAdd));
        offset += numberOfSymbolsToAdd;
      }
    }

    if (contentType == ConsoleViewContentType.USER_INPUT) {
      myDeferredUserInput.append(text);
    }

    ConsoleUtil.addToken(text.length(), info, contentType, myTokens);
    return new Pair<>(text, trimmedSymbolsNumber);
  }

  /**
   * IJ console works as follows - it receives managed process outputs from dedicated thread that serves that process and
   * pushes it to the {@link Document document} of editor used to represent process console. Important point here is that process
   * output is received in a control flow of the thread over than EDT but push to the document is performed from EDT. Hence, we
   * have a potential situation when particular process outputs a lot and EDT is busy or push to the document is performed slowly.
   * <p/>
   * We don't want to keep too many information from the underlying process then and want to trim text buffer that holds text
   * to push to the document then. Current method serves exactly that purpose, i.e. it's expected to be called when new chunk of
   * text is received from the underlying process and trims existing text buffer if necessary.
   *
   * @param numberOfNewSymbols number of symbols read from the managed process output
   * @return number of newly read symbols that should be accepted
   */
  private int trimDeferredOutputIfNecessary(final int numberOfNewSymbols) {
    if (!myUseCyclicBuffer || myDeferredOutputLength + numberOfNewSymbols <= myCyclicBufferSize) {
      return numberOfNewSymbols;
    }

    final int numberOfSymbolsToRemove = Math.min(myDeferredOutputLength, myDeferredOutputLength + numberOfNewSymbols - myCyclicBufferSize);

    if (DEBUG_PROCESSING) {
      log("Starting console trimming. Need to delete %d symbols (deferred output length: %d, number of new symbols: %d, "
          + "cyclic buffer size: %d). Current state:",
          numberOfSymbolsToRemove, myDeferredOutputLength, numberOfNewSymbols, myCyclicBufferSize
      );
      dumpDeferredOutput();
    }

    Context context = new Context(numberOfSymbolsToRemove);

    TIntArrayList indicesOfTokensToRemove = new TIntArrayList();
    for (int i = 0; i < myTokens.size(); i++) {
      ConsoleViewImpl.TokenInfo tokenInfo = myTokens.get(i);
      tokenInfo.startOffset -= context.removedSymbols;
      tokenInfo.endOffset -= context.removedSymbols;

      if (!context.canContinueProcessing()) {
        // Just update token offsets.
        if (context.removedSymbols == 0) {
          break;
        }
        continue;
      }

      int tokenLength = tokenInfo.getLength();

      // Don't remove input text.
      if (myContentTypesToNotStripOnCycling.contains(tokenInfo.contentType)) {
        skip(context, tokenLength);
        continue;
      }

      int removedTokenSymbolsNumber = remove(context, tokenLength);
      if (removedTokenSymbolsNumber == tokenLength) {
        indicesOfTokensToRemove.add(i);
      }
      else {
        tokenInfo.endOffset -= removedTokenSymbolsNumber;
      }
    }

    for (int i = indicesOfTokensToRemove.size() - 1; i >= 0; i--) {
      myTokens.remove(indicesOfTokensToRemove.get(i));
    }

    if (!myTokens.isEmpty()) {
      ConsoleViewImpl.TokenInfo tokenInfo = myTokens.get(0);
      if (tokenInfo.startOffset > 0) {
        final HyperlinkInfo hyperlinkInfo = tokenInfo.getHyperlinkInfo();
        ConsoleViewImpl.TokenInfo info = new ConsoleViewImpl.TokenInfo(ConsoleViewContentType.USER_INPUT, 0, tokenInfo.startOffset, hyperlinkInfo);
        myTokens.add(0, info);
      }
    }

    if (numberOfNewSymbols + myDeferredOutputLength > myCyclicBufferSize) {
      int result = myCyclicBufferSize - myDeferredOutputLength;
      if (result < 0) {
        return 0;
      }
      return result;
    }
    return numberOfNewSymbols;
  }

  private static void skip(@NotNull Context context, int symbolsToSkipNumber) {
    int remainingNumberOfBufferSymbols = context.currentBuffer.length() - context.bufferOffset;
    if (remainingNumberOfBufferSymbols < symbolsToSkipNumber) {
      symbolsToSkipNumber -= remainingNumberOfBufferSymbols;
      while (context.iterator.hasNext()) {
        context.currentBuffer = context.iterator.next();
        context.bufferOffset = 0;
        if (DEBUG_PROCESSING) {
          log("Switching to the next buffer. Number of token symbols to skip: %d", symbolsToSkipNumber);
        }
        if (symbolsToSkipNumber <= 0) {
          break;
        }
        if (context.currentBuffer.length() > symbolsToSkipNumber) {
          context.bufferOffset = symbolsToSkipNumber;
          symbolsToSkipNumber = 0;
          break;
        }
        else {
          symbolsToSkipNumber -= context.currentBuffer.length();
        }
      }
      assert symbolsToSkipNumber <= 0;
    }
    else {
      context.bufferOffset += symbolsToSkipNumber;
      if (DEBUG_PROCESSING) {
        log("All symbols to skip are processed. Current buffer offset is %d, text: '%s'", context.bufferOffset, context.currentBuffer);
      }
    }
  }

  private int remove(@NotNull Context context, int tokenLength) {
    int removedSymbolsNumber = 0;
    int remainingTotalNumberOfSymbolsToRemove = context.numberOfSymbolsToRemove - context.removedSymbols;
    int numberOfTokenSymbolsToRemove = Math.min(remainingTotalNumberOfSymbolsToRemove, tokenLength);
    while (numberOfTokenSymbolsToRemove > 0 && context.currentBuffer != null) {
      int diff = numberOfTokenSymbolsToRemove - (context.currentBuffer.length() - context.bufferOffset);
      int endDeleteBufferOffset = Math.min(context.bufferOffset + numberOfTokenSymbolsToRemove, context.currentBuffer.length());
      int numberOfSymbolsRemovedFromCurrentBuffer = endDeleteBufferOffset - context.bufferOffset;
      if (DEBUG_PROCESSING) {
        log("About to delete %d symbols from the current buffer (offset is %d). Removed symbols number: %d. Current buffer: %d: '%s'",
            numberOfSymbolsRemovedFromCurrentBuffer, context.bufferOffset, context.removedSymbols, context.currentBuffer.length(),
            StringUtil.convertLineSeparators(context.currentBuffer.toString()));
      }
      numberOfTokenSymbolsToRemove -= numberOfSymbolsRemovedFromCurrentBuffer;
      removedSymbolsNumber += numberOfSymbolsRemovedFromCurrentBuffer;
      context.removedSymbols += numberOfSymbolsRemovedFromCurrentBuffer;
      myDeferredOutputLength -= numberOfSymbolsRemovedFromCurrentBuffer;

      if (context.bufferOffset == 0 && (diff >= 0 || endDeleteBufferOffset == context.currentBuffer.length())) {
        context.iterator.remove();
        context.nextBuffer();
      }
      else {
        context.currentBuffer.delete(context.bufferOffset, endDeleteBufferOffset);
        if (DEBUG_PROCESSING) {
          log("Removed symbols at range [%d; %d). Buffer offset: %d, buffer length: %d, text: '%s'",
              context.bufferOffset, endDeleteBufferOffset, context.bufferOffset, context.currentBuffer.length(), context.currentBuffer);
        }
        if (context.bufferOffset == context.currentBuffer.length()) {
          context.nextBuffer();
        }
      }
    }
    return removedSymbolsNumber;
  }

  private final class Context {
    private final int numberOfSymbolsToRemove;
    StringBuilder currentBuffer;
    public final Iterator<StringBuilder> iterator;
    int bufferOffset;
    int removedSymbols;

    Context(int numberOfSymbolsToRemove) {
      this.numberOfSymbolsToRemove = numberOfSymbolsToRemove;
      iterator = myDeferredOutput.iterator();
      currentBuffer = iterator.hasNext() ? iterator.next() : null;
    }

    boolean canContinueProcessing() {
      return removedSymbols < numberOfSymbolsToRemove && currentBuffer != null;
    }

    boolean nextBuffer() {
      if (iterator.hasNext()) {
        currentBuffer = iterator.next();
        bufferOffset = 0;
        return true;
      }
      return false;
    }
  }

  private void dumpDeferredOutput() {
    if (!DEBUG_PROCESSING) {
      return;
    }
    log("Tokens:");
    for (ConsoleViewImpl.TokenInfo token : myTokens) {
      log("\t" + token);
    }
    log("Data:");
    for (StringBuilder buffer : myDeferredOutput) {
      log("\t%d: '%s'", buffer.length(), StringUtil.convertLineSeparators(buffer.toString()));
    }
    log("-----------------------------------------------------------------------------------------------------");
  }

  private static void log(Object o) {
  }

  private static void log(String message, Object... formatData) {
  }
}
