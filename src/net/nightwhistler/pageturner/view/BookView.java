/*
 * Copyright (C) 2011 Alex Kuiper
 * 
 * This file is part of PageTurner
 *
 * PageTurner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PageTurner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PageTurner.  If not, see <http://www.gnu.org/licenses/>.*
 */


package net.nightwhistler.pageturner.view;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.nightwhistler.pageturner.R;
import net.nightwhistler.pageturner.epub.PageTurnerSpine;
import net.nightwhistler.pageturner.epub.ResourceLoader;
import net.nightwhistler.pageturner.epub.ResourceLoader.ResourceCallback;
import net.nightwhistler.pageturner.html.CleanHtmlParser;
import net.nightwhistler.pageturner.html.TagNodeHandler;
import nl.siegmann.epublib.Constants;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.MediaType;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.domain.TOCReference;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.service.MediatypeService;
import nl.siegmann.epublib.util.StringUtil;

import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;


public class BookView extends ScrollView {
    	
	private int storedIndex;
	private String storedAnchor;
	
	private TextView childView;
	
	private Set<BookViewListener> listeners;
	
	private HtmlCleaner htmlCleaner;
	private CleanHtmlParser parser;
	
	private OnTouchListener touchListener;
	
	private PageTurnerSpine spine;
	
	private String fileName;
	private Book book;	
	
	private Map<String, Integer> anchors;
	
	private int prevIndex = -1;
	private int prevPos = -1;
	
	private PageChangeStrategy strategy;
	private ResourceLoader loader;		
	
	private int horizontalMargin = 0;
	private int verticalMargin = 0;
	private int lineSpacing = 0;
	
	private Bitmap backgroundBitmap;
	private int pixelsToDraw;
	
	private static final Logger LOG = LoggerFactory.getLogger(BookView.class);
	
	public BookView(Context context, AttributeSet attributes) {
		super(context, attributes);		
		
		this.listeners = new HashSet<BookViewListener>();
				
		this.childView = new TextView(context) {
			protected void onSizeChanged(int w, int h, int oldw, int oldh) {
				super.onSizeChanged(w, h, oldw, oldh);
				restorePosition();	
			}
			
			public boolean dispatchKeyEvent(KeyEvent event) {
				return BookView.this.dispatchKeyEvent(event);
			}	
			
			protected void onDraw(Canvas canvas) {
				super.onDraw(canvas);
				if ( backgroundBitmap != null ) {
					
					Rect source = new Rect( getHorizontalMargin(),							
							getVerticalMargin() + pixelsToDraw,
							backgroundBitmap.getWidth() - horizontalMargin,
							backgroundBitmap.getHeight() - verticalMargin );
					
					Rect dest = new Rect( 0, pixelsToDraw, 
							backgroundBitmap.getWidth() - (2*horizontalMargin), 
							backgroundBitmap.getHeight() - (2*verticalMargin) );					
					
					canvas.drawBitmap(backgroundBitmap, source, dest, null);
					
					Paint paint = new Paint();
					paint.setColor(Color.GRAY);
					paint.setStyle(Paint.Style.STROKE);
					
					canvas.drawLine(0, dest.top, dest.right, dest.top, paint);
				}	
			}
			
		};  
		
		childView.setLongClickable(true);	        
        this.setVerticalFadingEdgeEnabled(false);
        childView.setFocusable(true);
        childView.setLinksClickable(true);
        childView.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT) );
        
        MovementMethod m = childView.getMovementMethod();  
        if ((m == null) || !(m instanceof LinkMovementMethod)) {  
            if (childView.getLinksClickable()) {  
                childView.setMovementMethod(LinkMovementMethod.getInstance());  
            }  
        }  
        
        this.setSmoothScrollingEnabled(false);        
        this.addView(childView);
        
        this.htmlCleaner = createHtmlCleaner();   
        this.parser = new CleanHtmlParser();
        parser.registerHandler("img", new ImageTagHandler() );
        parser.registerHandler("a", new AnchorHandler(new LinkTagHandler()) );
        
        parser.registerHandler("h1", new AnchorHandler(parser.getHandlerFor("h1") ));
        parser.registerHandler("h2", new AnchorHandler(parser.getHandlerFor("h2") ));
        parser.registerHandler("h3", new AnchorHandler(parser.getHandlerFor("h3") ));
        parser.registerHandler("h4", new AnchorHandler(parser.getHandlerFor("h4") ));
        parser.registerHandler("h5", new AnchorHandler(parser.getHandlerFor("h5") ));
        parser.registerHandler("h6", new AnchorHandler(parser.getHandlerFor("h6") ));
        
        parser.registerHandler("p", new AnchorHandler(parser.getHandlerFor("p") ));
        
        this.anchors = new HashMap<String, Integer>();
	}	
	
	public void setFileName(String fileName) {
		this.fileName = fileName;
		this.loader = new ResourceLoader(fileName);
	}
	
	@Override
	public void setOnTouchListener(OnTouchListener l) {		
		super.setOnTouchListener(l);
		this.childView.setOnTouchListener(l);
		this.touchListener = l;
	}
	
	public void setStripWhiteSpace(boolean stripWhiteSpace) {
		this.parser.setStripExtraWhiteSpace(stripWhiteSpace);
	}
	
	public void setBackgroundBitmap(Bitmap backgroundBitmap) {
		this.backgroundBitmap = backgroundBitmap;			
	}
	
	public Bitmap getBackgroundBitmap() {
		return backgroundBitmap;
	}
	
	public void setPixelsToDraw(int pixelsToDraw) {
		this.pixelsToDraw = pixelsToDraw;
		invalidate();
	}	
	

	
	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		
		if ( this.touchListener != null ) {
			this.touchListener.onTouch(this, ev);
		}
		
		
		return super.onTouchEvent(ev);					
	}	
	
	public boolean hasPrevPosition() {
		return this.prevIndex != -1 && this.prevPos != -1;
	}

	public void setLineSpacing( int lineSpacing ) {
		if ( lineSpacing != this.lineSpacing ) {
			this.lineSpacing = lineSpacing;
			this.childView.setLineSpacing(lineSpacing, 1);
			
			if ( strategy != null ) {
				strategy.updatePosition();
			}
		}
	}
	
	public int getLineSpacing() {
		return lineSpacing;
	}
	
	public void setHorizontalMargin(int horizontalMargin) {
		
		if ( horizontalMargin != this.horizontalMargin ) {
			this.horizontalMargin = horizontalMargin;
			setPadding(this.horizontalMargin, this.verticalMargin, this.horizontalMargin, this.verticalMargin);
			if ( strategy != null ) {
				strategy.updatePosition();
			}
		}		
	}
	
	public void setVerticalMargin(int verticalMargin) {
		if ( verticalMargin != this.verticalMargin ) {
			this.verticalMargin = verticalMargin;
			setPadding(this.horizontalMargin, this.verticalMargin, this.horizontalMargin, this.verticalMargin);
			if ( strategy != null ) {
				strategy.updatePosition();
			}
		}		
	}	
	
	public int getHorizontalMargin() {
		return horizontalMargin;
	}
	
	public int getVerticalMargin() {
		return verticalMargin;
	}
	
	public void goBackInHistory() {
		
		this.strategy.clearText();
		this.spine.navigateByIndex( this.prevIndex );
		strategy.setPosition(this.prevPos);
		
		this.storedAnchor = null;
		this.prevIndex = -1;
		this.prevPos = -1;
		
		loadText();
	}
	
	public void clear() {
		this.childView.setText("");
		this.anchors.clear();
		this.storedAnchor = null;
		this.storedIndex = -1;		
		this.book = null;
		this.fileName = null;
		
		this.strategy.reset();
	}
	
	/**
	 * Loads the text and saves the restored position.
	 */
	public void restore() {
		strategy.clearText();
		loadText();
	}
	
	public void setIndex(int index) {
		this.storedIndex = index;
	}
	
	void loadText() {		
        new LoadTextTask().execute();        
	}
	
	public void setTypeface(Typeface typeFace) {
		this.childView.setTypeface( typeFace );
	}	
	
	public void pageDown() {		
		strategy.pageDown();
	}
	
	public void pageUp() {
		strategy.pageUp();
	}
	
	@Override
	public void scrollBy(int x, int y) {		
		super.scrollBy(x, y);		
		progressUpdate();
	}
	
	TextView getInnerView() {
		return childView;
	}
	
	PageTurnerSpine getSpine() {
		return this.spine;
	}
	
	@Override
	public void scrollTo(int x, int y) {		
		super.scrollTo(x, y);
		progressUpdate();
	}	
	
	/**
	 * Returns the full word containing the character at the selected location.
	 * 
	 * @param x
	 * @param y
	 * @return
	 */
	public CharSequence getWordAt( float x, float y ) {
		
		CharSequence text = this.childView.getText();
		
		if ( text.length() == 0 ) {
			return null;
		}
		
		Layout layout = this.childView.getLayout();
		int line = layout.getLineForVertical( (int) y);
		
		int offset = layout.getOffsetForHorizontal(line, x);
		
		if ( isBoundaryCharacter(text.charAt(offset)) ) {
			return null;
		}
		
		int left = Math.max(0,offset -1);
		int right = Math.min( text.length(), offset );
		
		CharSequence word = text.subSequence(left, right);
		while ( left > 0 && ! isBoundaryCharacter(word.charAt(0))) {
			left--;
			word = text.subSequence(left, right);
		}
		
		while ( right < text.length() && ! isBoundaryCharacter(word.charAt(word.length() -1))) {
			right++;
			word = text.subSequence(left, right);
		}
		
		int start = 0;
		int end = word.length();
		
		if ( isBoundaryCharacter(word.charAt(0))) {
			start = 1;
		}
		
		if ( isBoundaryCharacter(word.charAt(word.length() - 1))) {
			end = word.length() - 1;
		}
		
		return word.subSequence(start, end );
	}
	
	private static boolean isBoundaryCharacter( char c ) {
		char[] boundaryChars = { ' ', '.', ',','\"',
				'\'', '\n', '\t', ':'
		};
		
		for ( int i=0; i < boundaryChars.length; i++ ) {
			if (boundaryChars[i] == c) {
				return true;
			}		
		}
		
		return false;
	}
	
	public void navigateTo( String rawHref ) {
				
		this.prevIndex = this.getIndex();
		this.prevPos = this.getPosition();
		
		//URLDecode the href, so it does not contain %20 etc.
		String href = URLDecoder.decode( 
				StringUtil.substringBefore(rawHref, 
						Constants.FRAGMENT_SEPARATOR_CHAR) );
		
		//Don't decode the anchor.
		String anchor = StringUtil.substringAfterLast(rawHref, 
				Constants.FRAGMENT_SEPARATOR_CHAR); 
		
		if ( ! "".equals(anchor) ) {
			this.storedAnchor = anchor;
		}
		
		this.strategy.clearText();
		this.strategy.setPosition(0);
		
		if ( this.spine.navigateByHref(href) ) {
			loadText();
		} else {			
			new LoadTextTask().execute(href);
		}
	}	
	
	public List<TocEntry> getTableOfContents() {
		if ( this.book == null ) {
			return null;
		}
		
		List<TocEntry> result = new ArrayList<BookView.TocEntry>();
		
		flatten( book.getTableOfContents().getTocReferences(), result, 0 );
		
		return result;
	}
	
	private void flatten( List<TOCReference> refs, List<TocEntry> entries, int level ) {
		
		if ( refs == null || refs.isEmpty() ) {
			return;
		}
		
		for ( TOCReference ref: refs ) {
			
			String title = "";
			
			for ( int i = 0; i < level; i ++ ) {
				title += "-";
			}			
			
			title += ref.getTitle();
			
			if ( ref.getResource() != null ) {
				entries.add( new TocEntry(title, ref.getCompleteHref() ));
			}
			
			flatten( ref.getChildren(), entries, level + 1 );
		}
	}
	
	@Override
	public void fling(int velocityY) {
		strategy.clearStoredPosition();
		super.fling(velocityY);
	}
	
	public int getIndex() {
		if ( this.spine == null ) {
			return storedIndex;
		}
		
		return this.spine.getPosition();
	}	
	
	public int getPosition() {
		return strategy.getPosition();		
	}
	
	public void setPosition(int pos) {
		this.strategy.setPosition(pos);	
	}
	
	/**
	 * Scrolls to a previously stored point.
	 * 
	 * Call this after setPosition() to actually go there.
	 */
	private void restorePosition() {				
	
		if ( this.storedAnchor != null && this.anchors.containsKey(storedAnchor) ) {
			strategy.setPosition( anchors.get(storedAnchor) );
			this.storedAnchor = null;
		}
		
		this.strategy.updatePosition();
	}
	
	/**
	 * Many books use <p> and <h1> tags as anchor points.
	 * This class harvests those point by wrapping the original
	 * handler.
	 * 
	 * @author Alex Kuiper
	 *
	 */
	private class AnchorHandler extends TagNodeHandler {
		
		private TagNodeHandler wrappedHandler;
		
		public AnchorHandler(TagNodeHandler wrappedHandler) {
			this.wrappedHandler = wrappedHandler;
		}
		
		@Override
		public void handleTagNode(TagNode node, SpannableStringBuilder builder,
				int start, int end) {
			
			String id = node.getAttributeByName("id");
			if ( id != null ) {
				anchors.put(id, start);
			}
			
			wrappedHandler.handleTagNode(node, builder, start, end);
		}
	}
	
	/**
	 * Creates clickable links.
	 * 
	 * @author work
	 *
	 */
	private class LinkTagHandler extends TagNodeHandler {
		
		private List<String> externalProtocols;
		
		public LinkTagHandler() {
			this.externalProtocols = new ArrayList<String>();
			externalProtocols.add("http://");
			externalProtocols.add("https://");
			externalProtocols.add("http://");
			externalProtocols.add("ftp://");
			externalProtocols.add("mailto:");
		}
		
		@Override
		public void handleTagNode(TagNode node, SpannableStringBuilder builder,
				int start, int end) {
			
			final String href = node.getAttributeByName("href");
			
			if ( href == null ) {
				return;
			}
			
			//First check if it should be a normal URL link
			for ( String protocol: this.externalProtocols ) {
				if ( href.toLowerCase().startsWith(protocol)) {
					builder.setSpan(new URLSpan(href), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
					return;
				}
			}
			
			//If not, consider it an internal nav link.			
			ClickableSpan span = new ClickableSpan() {
					
				@Override
				public void onClick(View widget) {
					navigateTo(href);					
				}
			};
				
			builder.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);			 
		}
	}
	
	private class ImageCallback implements ResourceCallback {
		
		private SpannableStringBuilder builder;
		private int start;
		private int end;
		
		public ImageCallback(SpannableStringBuilder builder, int start, int end) {
			this.builder = builder;
			this.start = start;
			this.end = end;
		}
		
		@Override
		public void onLoadResource(String href, InputStream input) {
			
			Drawable drawable = null;
			try {				
				drawable = getDrawable(input);
			} catch (OutOfMemoryError outofmem) {
				LOG.error("Could not load image", outofmem);
			}
			
			if ( drawable == null ) {
				drawable = getResources().getDrawable(R.drawable.image_32x32);
			}
			
			
			builder.setSpan( new ImageSpan(drawable), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
						
		}
		
		private Drawable getDrawable(InputStream input) {
									
			BitmapDrawable draw = new BitmapDrawable(getResources(), input);
			
			int screenHeight = getHeight() - ( verticalMargin * 2);
			int screenWidth = getWidth() - ( horizontalMargin * 2 );
			
			if ( draw != null && draw.getBitmap() != null ) {
				int targetWidth = draw.getBitmap().getWidth();
				int targetHeight = draw.getBitmap().getHeight();

				//We scale to screen width for the cover or if the image is too wide.
				if ( targetWidth > screenWidth || spine.isCover() ) {
					
					double ratio = (double) draw.getBitmap().getHeight() / (double) draw.getBitmap().getWidth();
					
					targetWidth = screenWidth - 1;
					targetHeight = (int) (targetWidth * ratio);					
					
					if ( targetHeight >= screenHeight ) {
						ratio = (double) ( screenHeight - 10 ) / (targetHeight);
						
						targetHeight = screenHeight - 10;
						targetWidth = (int) (targetWidth * ratio);
					}
				}

				draw.setBounds(0, 0, targetWidth, targetHeight);					
			}
			
			return draw;					
		}
		
	}
	
	private class ImageTagHandler extends TagNodeHandler {
		
		@Override
		public void handleTagNode(TagNode node, SpannableStringBuilder builder,
				int start, int end) {						
			String src = node.getAttributeByName("src");
			
	        builder.append("\uFFFC");
	        
	        loader.registerCallback(src, new ImageCallback(builder, start, builder.length()));
		}				
		
	}
	
	@Override
	public void setBackgroundColor(int color) {
		super.setBackgroundColor(color);
		
		if ( this.childView != null ) {
			this.childView.setBackgroundColor(color);
		}
	}
	
	public void setTextColor( int color ) {
		if ( this.childView != null ) {
			this.childView.setTextColor(color);
		}
	}
	
	public static class TocEntry {
		private String title;
		private String href;
		
		public TocEntry(String title, String href) {
			this.title = title;
			this.href = href;
		}
		
		public String getHref() {
			return href;
		}
		
		public String getTitle() {
			return title;
		}
	}
	
	private TagNode processHtml(Resource resource) throws IOException {		
		return this.htmlCleaner.clean(resource.getReader());
	}
	
	private static HtmlCleaner createHtmlCleaner() {
		HtmlCleaner result = new HtmlCleaner();
		CleanerProperties cleanerProperties = result.getProperties();
		
		cleanerProperties.setAdvancedXmlEscape(true);
		
		cleanerProperties.setOmitXmlDeclaration(true);
		cleanerProperties.setOmitDoctypeDeclaration(false);		
		
		cleanerProperties.setTranslateSpecialEntities(true);
		cleanerProperties.setTransResCharsToNCR(true);
		cleanerProperties.setRecognizeUnicodeChars(true);
		
		cleanerProperties.setIgnoreQuestAndExclam(true);
		cleanerProperties.setUseEmptyElementTags(false);
		
		cleanerProperties.setPruneTags("script,style,title");
		
		return result;
	}	
	
	/**
	 * Sets the given text to be displayed, overriding the book.
	 * @param text
	 */
	public void setText(Spanned text) {
		this.strategy.loadText(text);
		this.strategy.updatePosition();
	}
	
	public Book getBook() {
		return book;
	}
	
	public float getTextSize() {
		return childView.getTextSize();
	}
	
	public void setTextSize(float textSize) {
		this.childView.setTextSize(textSize);
	}
	
	public void addListener(BookViewListener listener) {
		this.listeners.add( listener );
	}
	
	private void bookOpened( Book book ) {
		for ( BookViewListener listener: this.listeners ) {
			listener.bookOpened(book);
		}
	}	
	
	private void errorOnBookOpening( String errorMessage ) {
		for ( BookViewListener listener: this.listeners ) {
			listener.errorOnBookOpening(errorMessage);
		}
	}	 
	
	private void parseEntryStart( int entry) {
		for ( BookViewListener listener: this.listeners ) {
			listener.parseEntryStart(entry);
		}
	}	
	
	private void parseEntryComplete( int entry, String name ) {
		for ( BookViewListener listener: this.listeners ) {
			listener.parseEntryComplete(entry, name);
		}
	}
	
	private void progressUpdate() {		
		
		if ( this.spine != null ) {
			int progress = spine.getProgressPercentage(this.getPosition() );
		
			if ( progress != -1 ) {
				for ( BookViewListener listener: this.listeners ) {
					listener.progressUpdate(progress);
				}		
			}
		}
	}
	
	public void setEnableScrolling(boolean enableScrolling) {
		
		if ( this.strategy == null || this.strategy.isScrolling() != enableScrolling ) {

			int pos = -1;
			boolean wasNull = true;
			
			Spanned text = null;
			
			if ( this.strategy != null ) {
				pos = this.strategy.getPosition();
				text = this.strategy.getText();
				this.strategy.clearText();
				wasNull = false;
			}			

			if ( enableScrolling ) {
				this.strategy = new ScrollingStrategy(this);
			} else {
				this.strategy = new SinglePageStrategy(this);
			}

			if ( ! wasNull ) {				
				this.strategy.setPosition( pos );				 
			}
			
			if ( text != null && text.length() > 0 ) {
				this.strategy.loadText(text);
			} else {
				loadText();
			}
		}
	}
	
	public void setBook( Book book ) {
		
		this.book = book;
		this.spine = new PageTurnerSpine(book);	   
	    this.spine.navigateByIndex( this.storedIndex );	    
	}
	
	private void initBook() throws IOException {		
						
		// read epub file
        EpubReader epubReader = new EpubReader();	
       
        MediaType[] lazyTypes = {
        		MediatypeService.CSS, //We don't support CSS yet 
        		
        		MediatypeService.GIF, MediatypeService.JPG,
        		MediatypeService.PNG, MediatypeService.SVG, //Handled by the ResourceLoader
        		
        		MediatypeService.OPENTYPE, MediatypeService.TTF, //We don't support custom fonts either
        		MediatypeService.XPGT,
        };        	
        
       	Book newBook = epubReader.readEpubLazy(fileName, "UTF-8", Arrays.asList(lazyTypes));
        setBook( newBook );
	}	
	
	private class LoadTextTask extends AsyncTask<String, Integer, Spanned> {
		
		private String name;		
		
		private boolean wasBookLoaded;
		
		private String error;
		
		@Override
		protected void onPreExecute() {
			this.wasBookLoaded = book != null;
			parseEntryStart(getIndex());
		}
		
		protected Spanned doInBackground(String...hrefs) {	
			
			if ( loader != null ) {
				loader.clear();
			}
			
			if ( BookView.this.book == null ) {
				try {
					initBook();
				} catch (IOException io ) {
					this.error = io.getMessage();
					return null;
				}
			}			
									
			this.name = spine.getCurrentTitle();	
						
			Resource resource;
			
			if ( hrefs.length == 0 ) {
				resource = spine.getCurrentResource();
			} else {
				resource = book.getResources().getByHref(hrefs[0]);
			}
			
			if ( resource == null ) {
				return new SpannedString("Sorry, it looks like you clicked a dead link.\nEven books have 404s these days." );
			}			
			
			try {
				Spanned result = parser.fromTagNode( processHtml(resource) );
				loader.load(); //Load all image resources.
				return result;
			} catch (IOException io ) {
				return new SpannableString( "Could not load text: " + io.getMessage() );
			}			
	        
		}
		
		@Override
		protected void onPostExecute(final Spanned result) {
			
			if ( ! wasBookLoaded ) {
				if ( book != null ) {
					bookOpened(book);		
				} else {
					errorOnBookOpening(this.error);
					return;
				}
			}
			
			restorePosition();
			strategy.loadText( result );			
			
			parseEntryComplete(spine.getPosition(), this.name);
		}
	}	
}