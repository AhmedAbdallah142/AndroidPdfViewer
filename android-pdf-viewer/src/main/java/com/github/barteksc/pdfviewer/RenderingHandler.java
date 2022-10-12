/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.model.PagePart;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.Element;
import com.google.android.gms.vision.text.Line;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@link Handler} that will process incoming {@link RenderingTask} messages
 * and alert {@link PDFView#onBitmapRendered(PagePart)} when the portion of the
 * PDF is ready to render.
 */
class RenderingHandler extends Handler {
    /**
     * {@link Message#what} kind of message this handler processes.
     */
    static final int MSG_RENDER_TASK = 1;

    private static final String TAG = RenderingHandler.class.getName();

    private PDFView pdfView;

    private RectF renderBounds = new RectF();
    private Rect roundedRenderBounds = new Rect();
    private Matrix renderMatrix = new Matrix();
    private boolean running = false;
    TextRecognizer textRecognizer;

    private Map<Integer, List<RectF>> pagesBoundBoxes;
//    private boolean clearBoundBoxes = false;

    RenderingHandler(Looper looper, PDFView pdfView) {
        super(looper);
        this.pdfView = pdfView;
        textRecognizer = new TextRecognizer.Builder(pdfView.getContext()).build();
        pagesBoundBoxes = new HashMap<>();
    }

    void addRenderingTask(int page, float width, float height, RectF bounds, boolean thumbnail, int cacheOrder, boolean bestQuality, boolean annotationRendering, String searchWord) {
        RenderingTask task = new RenderingTask(width, height, bounds, page, thumbnail, cacheOrder, bestQuality, annotationRendering, searchWord);
        Message msg = obtainMessage(MSG_RENDER_TASK, task);
        sendMessage(msg);
    }

    @Override
    public void handleMessage(Message message) {
        RenderingTask task = (RenderingTask) message.obj;
        try {
            final PagePart part = proceed(task);
            if (part != null) {
                if (running) {
                    pdfView.post(new Runnable() {
                        @Override
                        public void run() {
                            pdfView.onBitmapRendered(part);
                        }
                    });
                } else {
                    part.getRenderedBitmap().recycle();
                }
            }
        } catch (final PageRenderingException ex) {
            pdfView.post(new Runnable() {
                @Override
                public void run() {
                    pdfView.onPageError(ex);
                }
            });
        }
    }

    private PagePart proceed(RenderingTask renderingTask) throws PageRenderingException {
        PdfFile pdfFile = pdfView.pdfFile;
        pdfFile.openPage(renderingTask.page);

        int w = Math.round(renderingTask.width);
        int h = Math.round(renderingTask.height);

        if (w == 0 || h == 0 || pdfFile.pageHasError(renderingTask.page)) {
            return null;
        }

        Bitmap render;
        try {
            render = Bitmap.createBitmap(w, h, renderingTask.bestQuality ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Cannot create bitmap", e);
            return null;
        }
        calculateBounds(w, h, renderingTask.bounds);

        pdfFile.renderPageBitmap(render, renderingTask.page, roundedRenderBounds, renderingTask.annotationRendering);

        if (renderingTask.thumbnail && !renderingTask.searchWord.isEmpty()) {
//            if (clearBoundBoxes) {
//                pagesBoundBoxes.clear();
//                clearBoundBoxes = false;
//            }
            pagesBoundBoxes.put(renderingTask.page, searchWordBoundingBox(renderingTask.searchWord, render));
        }
//        } else {
//            clearBoundBoxes = true;
//        }
//        System.out.println("is thumbnail: " + renderingTask.thumbnail + " page: " + renderingTask.page);
        List<RectF> boundingBoxes = pagesBoundBoxes.get(renderingTask.page);
        if (boundingBoxes != null) {
            for (RectF boundBox : boundingBoxes) {
                highlightBitmap(render, renderingTask.bounds, boundBox);
            }
        }
        return new PagePart(renderingTask.page, render, renderingTask.bounds, renderingTask.thumbnail, renderingTask.cacheOrder);
    }

    private void highlightBitmap(Bitmap bitmap, RectF bitmapBounds, RectF normalizedBoundingBox) {
        RectF realBoundBox = new RectF((normalizedBoundingBox.left - bitmapBounds.left) / (bitmapBounds.right - bitmapBounds.left) * bitmap.getWidth(), (normalizedBoundingBox.top - bitmapBounds.top) / (bitmapBounds.bottom - bitmapBounds.top) * bitmap.getHeight(), (normalizedBoundingBox.right - bitmapBounds.left) / (bitmapBounds.right - bitmapBounds.left) * bitmap.getWidth(), (normalizedBoundingBox.bottom - bitmapBounds.top) / (bitmapBounds.bottom - bitmapBounds.top) * bitmap.getHeight());
        Canvas canvas = new Canvas(bitmap);
        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setColor(Color.argb(120, 255, 255, 0));
        canvas.drawRect(realBoundBox, p);
    }

    private List<RectF> searchWordBoundingBox(String searchWord, Bitmap image) {
        List<RectF> boundingBoxes = new ArrayList<>();
        Frame imageFrame = new Frame.Builder().setBitmap(image).build();
        SparseArray<TextBlock> textBlocks = textRecognizer.detect(imageFrame);
        for (int i = 0; i < textBlocks.size(); i++) {
            TextBlock textBlock = textBlocks.valueAt(i);
            System.out.println(textBlock.getValue());
            List<Line> lines = (List<Line>) textBlock.getComponents();
            for (Line line : lines) {
                List<Element> elements = (List<Element>) line.getComponents();
                for (Element element : elements) {
                    if (Pattern.compile(Pattern.quote(searchWord), Pattern.CASE_INSENSITIVE).matcher(element.getValue()).find()) {
                        Rect boundBox = element.getBoundingBox();
                        RectF normalizedBoundBox = new RectF(boundBox.left / (float) image.getWidth(), boundBox.top / (float) image.getHeight(), boundBox.right / (float) image.getWidth(), boundBox.bottom / (float) image.getHeight());
                        boundingBoxes.add(normalizedBoundBox);
                    }
                }
            }
        }
        return boundingBoxes;
    }

    private void calculateBounds(int width, int height, RectF pageSliceBounds) {
        renderMatrix.reset();
        renderMatrix.postTranslate(-pageSliceBounds.left * width, -pageSliceBounds.top * height);
        renderMatrix.postScale(1 / pageSliceBounds.width(), 1 / pageSliceBounds.height());

        renderBounds.set(0, 0, width, height);
        renderMatrix.mapRect(renderBounds);
        renderBounds.round(roundedRenderBounds);
    }

    void stop() {
        running = false;
    }

    void start() {
        running = true;
    }

    private class RenderingTask {

        float width, height;

        RectF bounds;

        int page;

        boolean thumbnail;

        int cacheOrder;

        boolean bestQuality;

        boolean annotationRendering;

        String searchWord;

        RenderingTask(float width, float height, RectF bounds, int page, boolean thumbnail, int cacheOrder, boolean bestQuality, boolean annotationRendering, String searchWord) {
            this.page = page;
            this.width = width;
            this.height = height;
            this.bounds = bounds;
            this.thumbnail = thumbnail;
            this.cacheOrder = cacheOrder;
            this.bestQuality = bestQuality;
            this.annotationRendering = annotationRendering;
            this.searchWord = searchWord;
        }
    }
}
