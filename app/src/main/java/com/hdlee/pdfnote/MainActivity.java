package com.hdlee.pdfnote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import org.json.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity implements PdfPageView.Listener {
    /** The page area: a plain two-page row, or (split view) a grid of 2 to 4 panes placed by {@link #paneRect}. A touch goes to the pane under the finger. */
    private final class PaneLayout extends LinearLayout{
        PdfPageView routed;boolean grid;
        PaneLayout(Context c){super(c);}
        @Override protected void onMeasure(int w,int h){
            if(!grid){super.onMeasure(w,h);return;}
            int pw=MeasureSpec.getSize(w),ph=MeasureSpec.getSize(h);setMeasuredDimension(pw,ph);
            for(int i=0;i<getChildCount();i++){View c=getChildAt(i);if(c.getVisibility()==View.GONE)continue;int[] r=paneRect(i,pw,ph);c.measure(MeasureSpec.makeMeasureSpec(Math.max(0,r[2]-r[0]),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,r[3]-r[1]),MeasureSpec.EXACTLY));}
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            if(!grid){super.onLayout(changed,l,t,r,b);return;}
            for(int i=0;i<getChildCount();i++){View c=getChildAt(i);if(c.getVisibility()==View.GONE)continue;int[] rc=paneRect(i,r-l,b-t);c.layout(rc[0],rc[1],rc[2],rc[3]);}
        }
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){routed=null;if(splitMode&&renderer!=null){PdfPageView hitPane=paneAt(e.getX(),e.getY());if(hitPane!=null)splitFocusView(hitPane);}
                if(twoPage&&secondPageView.getVisibility()==View.VISIBLE&&firstPageView.getMatrix().isIdentity()&&secondPageView.getMatrix().isIdentity()){PdfPageView under=e.getX()<secondPageView.getLeft()?firstPageView:secondPageView;
                    for(PdfPageView v:new PdfPageView[]{firstPageView,secondPageView}){RectF r=v.pageRect();r.offset(v.getLeft(),v.getTop());if(r.contains(e.getX(),e.getY())){if(v!=under)routed=v;break;}}}}
            if(routed==null)return super.dispatchTouchEvent(e);
            MotionEvent c=MotionEvent.obtain(e);c.offsetLocation(-routed.getLeft(),-routed.getTop());boolean handled=routed.dispatchTouchEvent(c);c.recycle();
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)routed=null;return handled;
        }
    }
    private final class PageListener implements PdfPageView.Listener {
        PdfPageView view;
        private void active(){if(splitMode&&view!=null&&renderer!=null){splitFocusView(view);return;}if(view!=null&&renderer!=null&&pageView!=view){pageView=view;currentPage=view.getPageNumber();activeSession.page=currentPage;updateBookmarkButton();refreshStudyPanel();}}
        @Override public void onHighlightCreated(AnnotationStore.Mark mark){active();MainActivity.this.onHighlightCreated(mark);}
        @Override public void onMarkTapped(AnnotationStore.Mark mark){active();MainActivity.this.onMarkTapped(mark);}
        @Override public void onMemoPointRequested(int page, float x, float y){active();MainActivity.this.onMemoPointRequested(page,x,y);}
        @Override public void onZoomGestureStarted(){active();MainActivity.this.onZoomGestureStarted();}
        @Override public void onZoomChanged(float scale){if(MainActivity.this.pageView==view)MainActivity.this.updateZoomLabel(scale);}
        @Override public void onPageSwipe(int direction){active();MainActivity.this.onPageSwipe(direction);}
        @Override public void onOutlinePointRequested(int page, float x, float y){active();MainActivity.this.onOutlinePointRequested(page,x,y);}
        @Override public void onInkChanged(){active();MainActivity.this.onInkChanged();}
        @Override public void onTextSelectionFinished(PdfPageView.TextSelection selection, float anchorX, float anchorY){active();MainActivity.this.onTextSelectionFinished(selection,anchorX,anchorY);}
        @Override public void onTranslationTapped(AnnotationStore.TranslationNote note){active();MainActivity.this.onTranslationTapped(note);}
        @Override public void onSelectionAdjustStarted(){active();MainActivity.this.onSelectionAdjustStarted();}
        @Override public void onLassoSelectionFinished(){active();MainActivity.this.onLassoSelectionFinished();}
    @Override public void onElementTapped(AnnotationStore.PageElement element){active();MainActivity.this.onElementTapped(element);}
        @Override public void onElementDeleteRequested(AnnotationStore.PageElement element){active();MainActivity.this.deleteElement(element);}
        @Override public void onHyperlinkTapped(AnnotationStore.PageElement element,boolean longPress){active();MainActivity.this.onHyperlinkTapped(element,longPress);}
        @Override public void onBlankLongPress(int page,float x,float y,float viewX,float viewY){active();MainActivity.this.showInsertMenuAt(view,page,x,y,viewX,viewY);}
    }
    private static final class DocumentSession {
        Uri uri; String title; ParcelFileDescriptor descriptor; PdfRenderer renderer;
        AnnotationStore store; int page; File officePreview;
        final Deque<AnnotationStore.InkStroke> redoStrokes=new ArrayDeque<>();
        /** Ink removed by "페이지 전체 지우기" on {@code clearedPage}; the next undo brings it back (any other ink edit forgets it). */
        final List<AnnotationStore.InkStroke> clearedStrokes=new ArrayList<>();final List<AnnotationStore.Mark> clearedMarks=new ArrayList<>();int clearedPage=-1;
        final Map<Integer,List<PdfPageView.TextRegion>> textRegions=new HashMap<>();
    }
    private static final int EXPORT_PDF=30,IMPORT_IMAGE=31,IMPORT_VIDEO=32,IMPORT_TEMPLATE=33,EXPORT_ORIGINAL=34;
    private PaperChoiceView templateTarget;
    private LibraryRepository library; private LibraryDialog libraryDialog;
    private final Set<String> importing=new HashSet<>(); private final Set<DocumentSession> appending=new HashSet<>(); private boolean restoringSessions;
    private File libraryFolder;private Uri exportSource;private String exportSnapshot;private int exportPageCount;
    private String placementKind="",placementAsset="";
    private java.util.concurrent.atomic.AtomicBoolean searchCanceled;
    private static final int OPEN_PDF=10, EXPORT_JSON=11, NAVY=0xFF1C1C1E, ACCENT=0xFF007AFF, ACTIVE_BG=0xFFE5F0FF, ACTIVE_FG=0xFF007AFF;
    private PdfRenderer renderer; private ParcelFileDescriptor descriptor; private Uri documentUri;
    private String documentTitle="PDF"; private int currentPage, selectedColor=0x66FFDE59;
    private PdfPageView pageView,firstPageView,secondPageView;
    private boolean twoPage; private TextView titleView,pageLabel;
    private ImageButton bookmarkButton,memoButton,inkButton,fullscreenExit,previousOverlay,nextOverlay; private LinearLayout header,bottomBar,fullscreenDock,readBar,writeBar,stripBox,inkOptions; private ImageButton penButton,hlButton,eraserButton,eraserBarButton; private int inkOptionsKind=-1; private View dockHandle; private boolean dockShown; private final Runnable dockHider=()->hideFullscreenDock(true); private final java.util.Map<ImageButton,Integer> baseTint=new java.util.HashMap<>(); private boolean writeMode;
    private FrameLayout root; private AnnotationStore store; private boolean highlightMode,memoMode,outlineMode,fullscreen;
    private boolean verticalPageSwipe;
    private boolean fingerInk,swipeEnabled;
    /** Palm rejection for the tool bars (v1.55.0): while writing, a resting hand often lands on the bottom bar / pen strip. A finger that touches them while the pen is near (hover or touch, or within 0.6 s of it) or with a palm-sized contact is ignored. */
    private long penSeenTime=-100000;private boolean barPalmBlocked;
    private boolean penPointer(MotionEvent e,int i){int t=e.getToolType(i);return t==MotionEvent.TOOL_TYPE_STYLUS||t==MotionEvent.TOOL_TYPE_ERASER;}
    private boolean overBar(View v,float rawX,float rawY){if(v==null||v.getVisibility()!=View.VISIBLE||!v.isShown())return false;int[] loc=new int[2];v.getLocationOnScreen(loc);return rawX>=loc[0]-dp(4)&&rawX<=loc[0]+v.getWidth()+dp(4)&&rawY>=loc[1]-dp(4)&&rawY<=loc[1]+v.getHeight()+dp(4);}
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e){if(penPointer(e,0))penSeenTime=e.getEventTime();return super.dispatchGenericMotionEvent(e);}
    @Override public boolean dispatchTouchEvent(MotionEvent e){
        int am=e.getActionMasked();
        for(int i=0;i<e.getPointerCount();i++)if(penPointer(e,i))penSeenTime=e.getEventTime();
        if(am==MotionEvent.ACTION_DOWN){
            barPalmBlocked=false;
            if(writeMode&&!penPointer(e,0)){
                float mm=e.getTouchMajor()/Math.max(1f,getResources().getDisplayMetrics().xdpi)*25.4f;
                boolean palmLike=e.getToolType(0)==5/*TOOL_TYPE_PALM*/||mm>14f||e.getEventTime()-penSeenTime<600;
                if(palmLike&&(overBar(bottomBar,e.getRawX(),e.getRawY())||overBar(stripBox,e.getRawX(),e.getRawY())))barPalmBlocked=true;
            }
        }
        if(barPalmBlocked){if(am==MotionEvent.ACTION_UP||am==MotionEvent.ACTION_CANCEL)barPalmBlocked=false;return true;}
        return super.dispatchTouchEvent(e);
    }
    private HwpConversion hwpConversion;
    private final List<DocumentSession> sessions=new ArrayList<>(); private DocumentSession activeSession; private boolean splitMode,twoPageBeforeSplit;
    private final PdfPageView[] paneViews=new PdfPageView[4]; private final DocumentSession[] paneSessions=new DocumentSession[4]; private int paneCount,splitLayout; private float[] splitCols={1f},splitRows={1f}; private final List<View> splitDividers=new ArrayList<>(); private PaneLayout papersLayout;
    private ImageButton splitButton; private FrameLayout splitBar; private final TextView[] paneChip=new TextView[4]; private View papersView; private DocumentSession splitWantedSession; private long splitWantedAt; private boolean paneWanted; private long paneWantedAt;
    private LinearLayout tabRow,thumbnailList; private HorizontalScrollView tabStrip; private ScrollView thumbnailPanel;
    private int thumbnailGeneration; private boolean sidebarVisible;
    private int inkMode,inkPen,inkColor=0xFF1C1C1E; private float inkWidth=0.004f;
    /** The ink highlighter (형광펜): a pen of its own with its own colour / width; straight or freehand. The 하이라이트 mark tool (highlightMode) is a different, text-following tool. */
    private boolean inkHl,hlStraight; private int hlInkColor=0x66FFDE59; private float hlInkWidth=0.016f;
    private SharedPreferences recentPrefs;
    private TextRecognizer latinRecognizer,koreanRecognizer;
    private int ocrGeneration;
    private static final int TRANSLATE_EXTERNAL=12;
    private String pendingSource;
    private RectF pendingBounds;
    private DocumentSession pendingSession;
    private int pendingPage;
    private TextToSpeech speech;
    private boolean speechReady;
    private String speechPending;
    private Locale speechLocale=Locale.US;
    private boolean pageAnimating;
    private LinearLayout studySplit, studyRows, studyPanel;
    private FrameLayout pdfArea,viewportLayer;
    private TextView studyHeading;
    private boolean studyVisible, basketOnly;
    private static final int EXPORT_STUDY=20, IMPORT_SIDECAR=21;
    private static final int EXPORT_CAPTURE=22;
    private File pendingCaptureExport;
    private byte[] pendingExport;
    private AnnotationStore importTarget;
    private DocumentSession importSession;
    private String pendingJsonExport;
    private boolean awaitingOfficeReturn;
    private boolean officeConverting;
    private LinearLayout searchPanel,searchList; private ScrollView searchScroll; private EditText searchInput; private TextView searchStatus; private ImageButton textButton,lassoButton;
    private final List<SearchScanner.Hit> searchHits=new ArrayList<>(); private int searchCurrent=-1,searchSession,searchDone,searchTotal;
    private boolean searching,searchTruncated,searchPrecise; private DocumentSession searchOwner;
    private int lassoShape; private boolean showAllThumbnails,thumbInk;

    /** Translates on the device (ML Kit) and puts the result on the page right away; no other app and no copy-paste needed. */
    private void translateText(String source,RectF bounds){translateOffline(source,bounds);}

    private void readAloud(String source){
        String[] labels={"미국식 영어", "영국식 영어", "호주식 영어", "한국어", "읽기 중지"};
        Locale[] locales={Locale.US,Locale.UK,new Locale("en","AU"),Locale.KOREAN};
        new AlertDialog.Builder(this).setTitle("읽어주기 · 발음 선택").setItems(labels,(d,index)->{
            if(index==4){if(speech!=null)speech.stop();return;}
            speechLocale=locales[index];speechPending=source;
            if(speech==null){speech=new TextToSpeech(getApplicationContext(),status->{speechReady=status==TextToSpeech.SUCCESS;
                if(speechReady)runOnUiThread(this::speakPending);else runOnUiThread(()->toast("음성 엔진을 시작할 수 없습니다"));});}
            else if(speechReady)speakPending();
        }).show();
    }

    private void speakPending(){
        if(speech==null||!speechReady||speechPending==null)return;
        int available=speech.setLanguage(speechLocale);
        if(available==TextToSpeech.LANG_MISSING_DATA||available==TextToSpeech.LANG_NOT_SUPPORTED){speechPending=null;offerVoiceDownload();return;}
        speech.speak(speechPending,TextToSpeech.QUEUE_FLUSH,null,"pdf-note-selection");speechPending=null;
    }

    /** The chosen voice is not on the device: explain and offer the system's voice-data installer, then the TTS settings / Google TTS page as fallbacks. */
    private void offerVoiceDownload(){
        new AlertDialog.Builder(this).setTitle("음성이 없습니다").setMessage("선택한 발음의 음성이 기기에 없습니다.\n음성 데이터를 내려받으면 읽어주기를 쓸 수 있습니다.\nhttps://play.google.com/store/apps/details?id=com.google.android.tts")
            .setPositiveButton("음성 내려받기",(d,w)->{
                Intent[] tries={new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),new Intent("com.android.settings.TTS_SETTINGS"),new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id=com.google.android.tts")),new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"))};
                for(Intent i:tries){try{startActivity(i);return;}catch(RuntimeException ignored){}}
                toast("음성 설치 화면을 열 수 없습니다");
            }).setNegativeButton("닫기",null).show();
    }

    private void receiveExternalTranslation(int resultCode,Intent data){
        if(pendingSource==null||pendingSession==null)return;
        String source=pendingSource;RectF bounds=new RectF(pendingBounds);
        DocumentSession target=pendingSession;int page=pendingPage;
        pendingSource=null;pendingSession=null;
        if(!sessions.contains(target)){toast("원래 PDF를 다시 열어 번역하세요");return;}
        switchDocument(target);showPage(page);
        CharSequence translated=resultCode==RESULT_OK&&data!=null?data.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT):null;
        showTranslationResult(source,translated==null?"":translated.toString(),bounds);
    }

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);recentPrefs=getSharedPreferences("recent_documents",MODE_PRIVATE);verticalPageSwipe=recentPrefs.getBoolean("vertical_page_swipe",false);fingerInk=recentPrefs.getBoolean("finger_ink",false);swipeEnabled=recentPrefs.getBoolean("page_swipe_enabled_v2",true);twoPage=recentPrefs.getBoolean("two_page",false);library=new LibraryRepository(this);com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());libraryFolder=library.root;lassoShape=recentPrefs.getInt("lasso_shape",PdfPageView.LASSO_FREE);showAllThumbnails=recentPrefs.getBoolean("thumb_all",true);buildUi();pageView.setLassoShape(lassoShape);applyDarkPage();inkPen=recentPrefs.getInt("ink_pen",0);pageView.setInkPen(inkPen);hlInkColor=recentPrefs.getInt("hl_ink_color",hlInkColor);hlInkWidth=Math.max(.004f,Math.min(.04f,recentPrefs.getFloat("hl_ink_width",hlInkWidth)));thumbInk=recentPrefs.getBoolean("thumb_ink",false);selectedColor=(Math.max(26,Math.min(255,recentPrefs.getInt("highlight_alpha",Color.alpha(selectedColor))))<<24)|(selectedColor&0xFFFFFF);firstPageView.setEraserRadius(recentPrefs.getFloat("eraser_radius",10f));secondPageView.setEraserRadius(recentPrefs.getFloat("eraser_radius",10f));firstPageView.setEraserPartial(recentPrefs.getBoolean("eraser_partial",true));secondPageView.setEraserPartial(recentPrefs.getBoolean("eraser_partial",true));pageView.setFingerInk(fingerInk);pageView.setPageSwipeEnabled(swipeEnabled);pageView.setVerticalPageSwipe(verticalPageSwipe);syncOtherTools();Uri u=getIntent().getData();if(u!=null)openPdf(u);else if(!restoreSession())showWelcome();}
    private void applyKeepAwake(){if(recentPrefs.getBoolean("keep_awake",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onResume(){super.onResume();enforceChrome();applyKeepAwake();root.postDelayed(this::autoCheckForUpdate,4000);if(awaitingOfficeReturn){awaitingOfficeReturn=false;root.post(()->new AlertDialog.Builder(this).setTitle("문서로 돌아왔습니다")
        .setMessage("문서 앱에서 PDF로 내보냈다면 파일을 가져와 필기와 주석을 이어갈 수 있습니다.")
        .setPositiveButton("PDF 가져오기",(d,w)->chooseConvertedPdf()).setNegativeButton("나중에",null).show());}}
    private LinearLayout contentColumn;private View barGrip,dockGrip;private int insetBottom;private GradientDrawable barSurface;private EditText titleEdit;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private ImageButton icon(int image,String label,int tint,View.OnClickListener click){ImageButton b=new ImageButton(this);b.setImageResource(image);b.setColorFilter(tint);b.setContentDescription(label);b.setBackgroundColor(Color.TRANSPARENT);b.setScaleType(ImageView.ScaleType.CENTER);b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setOnClickListener(click);return b;}

    private void buildUi(){
        root=new FrameLayout(this);root.setBackgroundColor(0xFFF2F2F7);LinearLayout content=new LinearLayout(this);contentColumn=content;content.setOrientation(LinearLayout.VERTICAL);root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(4),0,dp(4),0);header.setBackgroundColor(0xFFF9F9F9);
        ImageButton libraryButton=icon(R.drawable.ic_folder_open,"문서함",NAVY,v->showLibrary());libraryButton.setColorFilter(null);libraryButton.setImageDrawable(new FolderIconDrawable(LibraryRepository.FOLDER_COLORS[1],dp(30)));libraryButton.setBackground(round(Color.WHITE,16));libraryButton.setPadding(dp(5),dp(5),dp(5),dp(5));LinearLayout.LayoutParams libraryParams=new LinearLayout.LayoutParams(dp(42),dp(42));libraryParams.setMargins(dp(8),0,dp(2),0);header.addView(libraryButton,libraryParams);
        titleView=new TextView(this);titleView.setTextColor(NAVY);titleView.setTextSize(15);titleView.setTypeface(Typeface.DEFAULT_BOLD);titleView.setTag("document_title");titleView.setOnClickListener(v->{if(activeSession!=null)beginTitleEdit();else showLibrary();});titleView.setSingleLine();titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);titleView.setTextDirection(View.TEXT_DIRECTION_LTR);titleView.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);titleView.setPadding(dp(8),0,dp(8),0);titleView.setBackground(round(Color.WHITE,18));titleView.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*.46f));titleView.setPadding(dp(14),0,dp(14),0);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-2,dp(36));titleParams.setMargins(dp(6),0,dp(6),0);header.addView(titleView,titleParams);header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        header.addOnLayoutChangeListener((v,l,t,rr,b,ol,ot,or,ob)->{int w=rr-l;if(w<=0)return;int max=Math.max(dp(60),Math.min(Math.round(w*.46f),w-dp(300)));if(titleView.getMaxWidth()!=max)titleView.setMaxWidth(max);});
        header.addView(icon(R.drawable.ic_sidebar,"페이지 목록",0xFF007AFF,v->toggleSidebar()),new LinearLayout.LayoutParams(dp(44),dp(52)));
        header.addView(icon(R.drawable.ic_search,"문서·필기 검색",0xFF30B0C7,v->searchDocument()),new LinearLayout.LayoutParams(dp(44),dp(52)));
        splitButton=icon(R.drawable.ic_dual,"분할 보기 · 문서 두 개를 나란히",0xFF5856D6,v->toggleSplit());header.addView(splitButton,new LinearLayout.LayoutParams(dp(44),dp(52)));
        header.addView(icon(R.drawable.ic_fullscreen,"전체 화면",0xFFAF52DE,v->toggleFullscreen()),new LinearLayout.LayoutParams(dp(44),dp(52)));
        header.addView(icon(R.drawable.ic_more_vert,"도구",NAVY,v->showMainMenu(v,false)),new LinearLayout.LayoutParams(dp(44),dp(52)));content.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        tabStrip=new HorizontalScrollView(this);tabStrip.setHorizontalScrollBarEnabled(false);tabStrip.setBackgroundColor(0xFFF7F7F7);tabRow=new LinearLayout(this);tabRow.setGravity(Gravity.CENTER_VERTICAL);tabRow.setPadding(dp(6),dp(4),dp(6),dp(4));tabStrip.addView(tabRow,new HorizontalScrollView.LayoutParams(-2,-1));content.addView(tabStrip,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout viewerRow=new LinearLayout(this);viewerRow.setOrientation(LinearLayout.HORIZONTAL);
        thumbnailPanel=new ScrollView(this);thumbnailPanel.setBackgroundColor(0xFFF2F2F7);thumbnailList=new LinearLayout(this);thumbnailList.setOrientation(LinearLayout.VERTICAL);thumbnailList.setPadding(dp(8),dp(14),dp(8),dp(96));thumbnailPanel.addView(thumbnailList,new ScrollView.LayoutParams(-1,-2));buildSearchPanel();buildSidePanel();viewerRow.addView(sidePanel,new LinearLayout.LayoutParams(sidePanelWidth(),-1));
        sideResizer=new View(this){@Override protected void onDraw(Canvas c){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0xFFC7C7CC);float cx=getWidth()/2f,cy=getHeight()/2f;c.drawRoundRect(cx-dp(2),cy-dp(18),cx+dp(2),cy+dp(18),dp(2),dp(2),p);}};
        sideResizer.setTag("side_resizer");sideResizer.setContentDescription("미리보기 폭 조절 · 좌우로 드래그");sideResizer.setBackgroundColor(0xFFEDEDF0);sideResizer.setVisibility(View.GONE);
        sideResizer.setOnTouchListener(new View.OnTouchListener(){float startX;int startWidth;
            @Override public boolean onTouch(View v,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:startX=e.getRawX();startWidth=sidePanel.getWidth();v.getParent().requestDisallowInterceptTouchEvent(true);return true;
                    case MotionEvent.ACTION_MOVE:{int w=clampSideWidth(Math.round(startWidth+e.getRawX()-startX));LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)sidePanel.getLayoutParams();if(lp.width!=w){lp.width=w;sidePanel.setLayoutParams(lp);}return true;}
                    case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:{int w=clampSideWidth(Math.round(startWidth+e.getRawX()-startX));recentPrefs.edit().putInt("side_width_dp",Math.round(w/getResources().getDisplayMetrics().density)).apply();rebuildThumbnails();return true;}
                }
                return true;
            }});
        viewerRow.addView(sideResizer,new LinearLayout.LayoutParams(dp(12),-1));
        FrameLayout viewport=new FrameLayout(this){
            float downX,downY;boolean tracking,stolen;
            /** In full screen a finger swipe up from just above the system gesture area brings the tool dock back. */
            @Override public boolean dispatchTouchEvent(MotionEvent e){
                int a=e.getActionMasked();
                if(!fullscreen||fullscreenDock==null){stolen=false;return super.dispatchTouchEvent(e);}
                if(a==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();stolen=false;tracking=!dockShown&&e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER&&!(inkMode!=0&&fingerInk)&&downY>getHeight()-dp(170);}
                else if(a==MotionEvent.ACTION_MOVE&&tracking&&!stolen){float dy=downY-e.getY(),dx=Math.abs(e.getX()-downX);if(dy>dp(30)&&dy>dx*1.4f){stolen=true;showFullscreenDock(false);MotionEvent c=MotionEvent.obtain(e);c.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(c);c.recycle();return true;}}
                if(stolen){if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){stolen=false;tracking=false;}return true;}
                return super.dispatchTouchEvent(e);
            }
        };viewportLayer=viewport;installDrop(viewport);
        // two-page spread: the pages touch at the seam and a zoomed page may reach into the other half, so a gesture goes to the page under the finger (not the half)
        PaneLayout papers=new PaneLayout(this);papersLayout=papers;papers.setClipChildren(false);papers.setMotionEventSplittingEnabled(false);
        PageListener firstListener=new PageListener(),secondListener=new PageListener();
        firstPageView=new PdfPageView(this,firstListener);secondPageView=new PdfPageView(this,secondListener);firstListener.view=firstPageView;secondListener.view=secondPageView;pageView=firstPageView;
        firstPageView.setPageDrag(pageDragHandler);secondPageView.setPageDrag(pageDragHandler);papers.addView(firstPageView,new LinearLayout.LayoutParams(0,-1,1));papers.addView(secondPageView,new LinearLayout.LayoutParams(0,-1,1));paneViews[0]=firstPageView;paneViews[1]=secondPageView;for(int pi=2;pi<4;pi++){PageListener pl=new PageListener();PdfPageView pv=new PdfPageView(this,pl);pl.view=pv;pv.setPageDrag(pageDragHandler);pv.setVisibility(View.GONE);paneViews[pi]=pv;papers.addView(pv,new LinearLayout.LayoutParams(0,-1,1));}secondPageView.setVisibility(twoPage?View.VISIBLE:View.GONE);viewport.addView(papers,new FrameLayout.LayoutParams(-1,-1));papersView=papers;buildSplitBar(viewport);installSplitChrome(viewport,papers);
        previousOverlay=icon(R.drawable.ic_chevron_left,"이전 페이지",NAVY,v->animatePage(-1));nextOverlay=icon(R.drawable.ic_chevron_right,"다음 페이지",NAVY,v->animatePage(1));
        ImageButton[] arrows={previousOverlay,nextOverlay};for(int i=0;i<arrows.length;i++){ImageButton arrow=arrows[i];arrow.setBackground(round(0xE6F7F5FF,26));arrow.setAlpha(0.38f);arrow.setElevation(dp(3));FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.CENTER_VERTICAL|(i==0?Gravity.START:Gravity.END));ap.setMargins(dp(8),0,dp(8),0);viewport.addView(arrow,ap);}
        zoomPanel=new LinearLayout(this);zoomPanel.setTag("zoom_panel");zoomPanel.setOrientation(LinearLayout.VERTICAL);zoomPanel.setGravity(Gravity.CENTER_HORIZONTAL);zoomPanel.setBackground(round(0xF2FFFFFF,22));zoomPanel.setElevation(dp(4));zoomPanel.setPadding(0,dp(2),0,dp(2));
        TextView zoomIn=stepButton("＋","확대");zoomIn.setBackground(null);zoomIn.setOnClickListener(v->stepZoom(1.25f));zoomPanel.addView(zoomIn,new LinearLayout.LayoutParams(dp(40),dp(36)));
        zoomLabel=new TextView(this);zoomLabel.setTag("zoom_label");zoomLabel.setText("100%");zoomLabel.setTextSize(11);zoomLabel.setTextColor(NAVY);zoomLabel.setGravity(Gravity.CENTER);zoomLabel.setContentDescription("확대 비율 · 눌러 100%");zoomLabel.setOnClickListener(v->{if(pageView!=null)pageView.setZoom(1f);});zoomPanel.addView(zoomLabel,new LinearLayout.LayoutParams(dp(40),dp(22)));
        TextView zoomOut=stepButton("−","축소");zoomOut.setBackground(null);zoomOut.setOnClickListener(v->stepZoom(0.8f));zoomPanel.addView(zoomOut,new LinearLayout.LayoutParams(dp(40),dp(36)));
        FrameLayout.LayoutParams zoomParams=new FrameLayout.LayoutParams(dp(40),-2,Gravity.START|Gravity.BOTTOM);zoomParams.setMargins(dp(10),0,0,dp(16));viewport.addView(zoomPanel,zoomParams);
        viewerRow.addView(viewport,new LinearLayout.LayoutParams(0,-1,1));
        pdfArea=new FrameLayout(this);pdfArea.addView(viewerRow,new FrameLayout.LayoutParams(-1,-1));
        studySplit=new LinearLayout(this);studySplit.addView(pdfArea);buildStudyPanel();studySplit.addView(studyPanel);
        content.addView(studySplit,new LinearLayout.LayoutParams(-1,0,1));layoutStudyPanel();
        bottomBar=new LinearLayout(this);bottomBar.setTag("reading_toolbar");bottomBar.setGravity(Gravity.CENTER_VERTICAL);bottomBar.setPadding(dp(6),0,dp(6),0);barSurface=new GradientDrawable();barSurface.setColor(Color.WHITE);barSurface.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});bottomBar.setBackground(barSurface);bottomBar.setElevation(dp(8));
        readBar=new LinearLayout(this);readBar.setTag("read_bar");readBar.setGravity(Gravity.CENTER_VERTICAL);writeBar=new LinearLayout(this);writeBar.setTag("writing_toolbar");writeBar.setGravity(Gravity.CENTER_VERTICAL);writeBar.setVisibility(View.VISIBLE);writeBar.setTag("writing_toolbar");
        pageLabel=new TextView(this);pageLabel.setTag("page_indicator");pageLabel.setGravity(Gravity.CENTER);pageLabel.setIncludeFontPadding(false);pageLabel.setPadding(0,0,0,0);pageLabel.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);pageLabel.setTextColor(0xFF8E8E93);pageLabel.setTextSize(11);pageLabel.setSingleLine();pageLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);pageLabel.setBackground(round(0xFFF2F2F7,10));pageLabel.setContentDescription("페이지 번호 · 눌러 이동");pageLabel.setOnClickListener(v->{if(renderer==null)showAddDocumentMenu();else goToPage();});LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(64),dp(32));pp.setMargins(0,0,dp(5),0);readBar.addView(pageLabel,pp);
        pageLabel.setOnClickListener(v->{if(renderer==null)showAddDocumentMenu();else showPageJumpMenu(v);});pageLabel.setContentDescription("페이지 이동");
        barIcon(readBar,R.drawable.ic_eye,"보기 방법",0xFF30B0C7,v->showViewMenu(v));
        readButton=barIcon(readBar,R.drawable.ic_book,"읽기 모드",0xFF007AFF,v->setWriteMode(false));
        inkButton=barIcon(readBar,R.drawable.ic_ink,"필기 모드",0xFF5856D6,v->inkModeTap());
        eraserBarButton=barIcon(readBar,R.drawable.ic_eraser,"지우개",0xFFFF6B8A,v->eraserBarTap(v));
        textButton=barIcon(readBar,R.drawable.ic_text,"타이핑",0xFF34C759,v->toggleTyping());
        bookmarkButton=barIcon(readBar,R.drawable.ic_star_outline,"즐겨찾기",0xFFF5A623,v->toggleBookmark());
        lassoButton=barIcon(readBar,R.drawable.ic_lasso,"올가미 선택",0xFFAF52DE,v->toggleLasso(v));
        insertButton=barIcon(readBar,R.drawable.ic_insert,"삽입 · 메모 사진 스티커 도형 표",0xFFFF2D55,v->showInsertMenu(v));
        writeBar.setBackground(round(0xF8FFFFFF,22));writeBar.setElevation(dp(8));writeBar.setPadding(dp(4),0,dp(4),0);
        penButton=stripIcon(R.drawable.ic_ink,"펜",0xFF1C1C1E,v->penTap(v));
        hlButton=stripIcon(R.drawable.ic_highlight,"형광펜",0xFFF5C400,v->highlightTap(v));
        eraserButton=stripIcon(R.drawable.ic_eraser,"지우개",0xFFFF6B8A,v->eraserTap(v));
        stripIcon(R.drawable.ic_undo,"실행 취소",0xFF8E8E93,v->undoInk());
        stripIcon(R.drawable.ic_redo,"다시 실행",0xFF8E8E93,v->redoInk());
        writeBar.addView(makeGrip(stripBox=new LinearLayout(this),"strip"),0,new LinearLayout.LayoutParams(dp(18),dp(44)));   // the strip floats: drag the dotted grip to move it
        stripBox.setOrientation(LinearLayout.VERTICAL);stripBox.setGravity(Gravity.CENTER_HORIZONTAL);stripBox.setVisibility(View.GONE);stripBox.setTag("write_strip_box");stripBox.addView(writeBar,new LinearLayout.LayoutParams(-2,dp(44)));
        inkOptions=new LinearLayout(this);inkOptions.setTag("ink_options");inkOptions.setOrientation(LinearLayout.VERTICAL);inkOptions.setBackground(round(0xF8FFFFFF,18));inkOptions.setElevation(dp(8));inkOptions.setPadding(dp(6),dp(4),dp(6),dp(2));inkOptions.setVisibility(View.GONE);
        LinearLayout.LayoutParams optionsParams=new LinearLayout.LayoutParams(dp(280),-2);optionsParams.topMargin=dp(6);stripBox.addView(inkOptions,optionsParams);
        FrameLayout.LayoutParams stripParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);stripParams.topMargin=dp(8);viewport.addView(stripBox,stripParams);
        barGrip=makeGrip(bottomBar,"bar");barGrip.setVisibility(View.GONE);bottomBar.addView(barGrip,new LinearLayout.LayoutParams(dp(18),dp(44)));bottomBar.addView(readBar,new LinearLayout.LayoutParams(0,-1,1));barMenuButton=icon(R.drawable.ic_float,"하단 메뉴 위치·방향",0xFF8E8E93,v->showBarLayoutMenu(v));barMenuButton.setTag("bar_layout");barMenuButton.setPadding(dp(8),dp(8),dp(8),dp(8));bottomBar.addView(barMenuButton,new LinearLayout.LayoutParams(dp(38),dp(44)));content.addView(bottomBar,new LinearLayout.LayoutParams(-1,dp(54)));
        fullscreenDock=new LinearLayout(this);fullscreenDock.setTag("fullscreen_toolbar");fullscreenDock.setGravity(Gravity.CENTER_VERTICAL);fullscreenDock.setPadding(dp(6),0,dp(6),0);GradientDrawable dockSurface=round(Color.WHITE,24);dockSurface.setStroke(Math.max(1,dp(1)/2),0x14000000);fullscreenDock.setBackground(dockSurface);fullscreenDock.setElevation(dp(10));   // same solid surface and coloured icons as the normal bottom barfullscreenDock.setVisibility(View.GONE);
        fullscreenDock.addView(dockIcon(R.drawable.ic_outline,"전체 화면 개요",0xFF007AFF,v->showOutlineList()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_eye,"전체 화면 보기 방법",0xFF30B0C7,v->showViewMenu(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_ink,"전체 화면 필기도구",0xFF5856D6,v->penTap(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_eraser,"전체 화면 지우개",0xFFFF6B8A,v->eraserBarTap(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_insert,"전체 화면 삽입",0xFFFF2D55,v->showInsertMenu(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_text,"전체 화면 타이핑",0xFF34C759,v->toggleTyping()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_lasso,"전체 화면 올가미",0xFFAF52DE,v->toggleLasso(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_more_vert,"전체 화면 메뉴",NAVY,v->showMainMenu(v,true)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        dockGrip=makeGrip(fullscreenDock,"dock");dockGrip.setVisibility(View.GONE);fullscreenDock.addView(dockGrip,0,new LinearLayout.LayoutParams(dp(18),dp(44)));fullscreenExit=dockIcon(R.drawable.ic_fullscreen_exit,"전체 화면 종료",0xFF8E8E93,v->toggleFullscreen());fullscreenDock.addView(fullscreenExit,new LinearLayout.LayoutParams(dp(44),dp(44)));FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ep.setMargins(dp(8),0,dp(8),dp(14));root.addView(fullscreenDock,ep);
        fullscreenDock.setOnTouchListener((v,e)->{if(dockShown){v.removeCallbacks(dockHider);if(!dockPinned()&&(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL))v.postDelayed(dockHider,6000);}return false;});
        ImageView handleIcon=new ImageView(this);handleIcon.setImageResource(R.drawable.ic_chevron_up);handleIcon.setColorFilter(Color.WHITE);handleIcon.setPadding(dp(14),dp(2),dp(14),dp(2));dockHandle=handleIcon;dockHandle.setTag("fullscreen_handle");dockHandle.setContentDescription("도구 모음 열기 · 위로 쓸어올리기");dockHandle.setBackground(round(0x8C000000,14));dockHandle.setVisibility(View.GONE);dockHandle.setOnClickListener(v->showFullscreenDock(false));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(56),dp(28),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);hp.bottomMargin=dp(14);root.addView(dockHandle,hp);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(fullscreen){boolean shown=Build.VERSION.SDK_INT>=30?insets.isVisible(WindowInsets.Type.navigationBars())||insets.isVisible(WindowInsets.Type.statusBars()):insets.getSystemWindowInsetBottom()>0;if(shown){root.removeCallbacks(barHider);root.postDelayed(barHider,400);}}int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());top=b.top;bottom=b.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}insetBottom=bottom;insetTop=top;if(!fullscreen){header.setPadding(dp(4),top,dp(4),0);header.getLayoutParams().height=dp(52)+top;if(!floatBar()){bottomBar.setPadding(dp(6),0,dp(6),bottom);bottomBar.getLayoutParams().height=dp(54)+bottom;}else if(barPlace().equals("float")&&!barVertical()){((FrameLayout.LayoutParams)bottomBar.getLayoutParams()).bottomMargin=dp(14)+bottom;bottomBar.requestLayout();}else if(!barPlace().equals("float")){bottomBar.setPadding(dp(4),top+dp(4),dp(4),bottom+dp(4));}}FrameLayout.LayoutParams dock=(FrameLayout.LayoutParams)fullscreenDock.getLayoutParams();dock.bottomMargin=dp(14)+bottom;fullscreenDock.setLayoutParams(dock);FrameLayout.LayoutParams handle=(FrameLayout.LayoutParams)dockHandle.getLayoutParams();handle.bottomMargin=dp(14)+bottom;dockHandle.setLayoutParams(handle);return insets;});setContentView(root);if(floatBar())applyBarMode();
    }
    private ImageButton dockIcon(int resource,String description,int tint,View.OnClickListener action){ImageButton button=icon(resource,description,tint,action);button.setPadding(dp(10),dp(10),dp(10),dp(10));return button;}
    private ImageButton barIcon(LinearLayout bar,int resource,String description,int tint,View.OnClickListener action){
        ImageButton button=icon(resource,description,tint,action);button.setPadding(dp(6),dp(6),dp(6),dp(6));baseTint.put(button,tint);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(2),0,dp(2),0);bar.addView(button,p);return button;
    }
    /** Samsung Notes style: reading mode shows page tools, writing mode shows pen tools. */
    private void setWriteMode(boolean on){
        if(on&&renderer==null){toast("문서를 먼저 여세요");return;}
        writeMode=on;updateWriteStrip();
        if(on){if(inkMode==0&&!highlightMode&&!memoMode&&!outlineMode&&!pageView.isLassoMode())setInkMode(1);else updateToolStates();}
        else if(renderer!=null)setInkMode(0);else updateToolStates();
    }
    private void penTap(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(!writeMode){setWriteMode(true);return;}
        if(!inkHl&&(inkMode==1||inkMode==3))showPenMenu(anchor);else{inkHl=false;setInkMode(1);}
    }
    private void highlightTap(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(inkHl&&(inkMode==1||inkMode==3))showHighlightMenu(anchor);else{if(!writeMode)setWriteMode(true);inkHl=true;setInkMode(hlStraight?3:1);}
    }
    private ImageButton stripIcon(int resource,String description,int tint,View.OnClickListener action){
        ImageButton button=icon(resource,description,tint,action);button.setPadding(dp(10),dp(10),dp(10),dp(10));baseTint.put(button,tint);writeBar.addView(button,new LinearLayout.LayoutParams(dp(44),dp(44)));return button;
    }
    private boolean writeStripShown(){return recentPrefs==null||recentPrefs.getBoolean("write_strip",true);}
    /** The pen / highlighter / eraser strip floats on the page while writing; it can be kept visible or hidden (the choice is remembered). */
    private void setWriteStripShown(boolean on){recentPrefs.edit().putBoolean("write_strip",on).apply();updateWriteStrip();toast(on?"필기 도구 줄을 계속 보여줍니다":"필기 도구 줄을 숨겼습니다. 필기 모드 단추를 눌러 다시 볼 수 있습니다");}
    private void updateWriteStrip(){
        boolean show=writeMode&&writeStripShown();if(stripBox!=null){boolean was=stripBox.getVisibility()==View.VISIBLE;stripBox.setVisibility(show?View.VISIBLE:View.GONE);if(show&&!was)applyFloatPos(stripBox,"strip");}
        positionTopTools();
    }
    /** The pen strip floats at the top of the page, below the split-view name bar when that is shown. */
    private void positionTopTools(){
        if(stripBox==null)return;boolean strip=writeMode&&writeStripShown();int base=dp(8)+(splitBar!=null&&splitBar.getVisibility()==View.VISIBLE?dp(40):0);
        FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)stripBox.getLayoutParams();if(sp.topMargin!=base){sp.topMargin=base;stripBox.setLayoutParams(sp);}
    }
    /** Writing-mode button: switches to writing; pressed again while writing it shows or hides the tool strip. */
    private void inkModeTap(){
        if(!writeMode){setWriteMode(true);if(!writeStripShown())toast("필기 도구 줄이 숨겨져 있습니다. 필기 모드 단추를 한 번 더 누르면 나타납니다");return;}
        setWriteStripShown(!writeStripShown());
    }
    private void showPageJumpMenu(View anchor){
        int count=renderer.getPageCount();
        AnchoredMenu.show(this,anchor,true,AnchoredMenu.rows(
            new AnchoredMenu.Row("페이지로 이동",R.drawable.ic_page,this::goToPage).tint(0xFF30B0C7),
            new AnchoredMenu.Row("이전 페이지",R.drawable.ic_chevron_left,()->animatePage(-1)).tint(0xFF8E8E93),
            new AnchoredMenu.Row("다음 페이지",R.drawable.ic_chevron_right,()->animatePage(1)).tint(0xFF8E8E93),
            new AnchoredMenu.Row("처음 페이지",R.drawable.ic_chevron_up,()->showPage(0)).tint(0xFF8E8E93),
            new AnchoredMenu.Row("마지막 페이지",R.drawable.ic_chevron_down,()->showPage(count-1)).tint(0xFF8E8E93),
            new AnchoredMenu.Row("문서 개요",R.drawable.ic_outline,this::showOutlineList).tint(0xFF007AFF)),null);
    }
    /** Split view: a blue frame around the active pane, and the page-turn arrows sit on both sides of that pane. */
    private void installSplitChrome(FrameLayout viewport,View papers){
        final float d=getResources().getDisplayMetrics().density;
        paneFrame=new View(this){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){float w=5*d;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w);p.setColor(0xFF007AFF);c.drawRect(w/2,w/2,getWidth()-w/2,getHeight()-w/2,p);}};
        paneFrame.setVisibility(View.GONE);paneFrame.setTag("split_active_frame");viewport.addView(paneFrame,new FrameLayout.LayoutParams(0,0,Gravity.TOP|Gravity.START));
        papers.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateSplitChrome());
    }
    private void updateSplitChrome(){
        if(paneFrame==null||firstPageView==null||previousOverlay==null)return;
        PdfPageView pane=pageView==null?firstPageView:pageView;
        positionTopTools();positionSplitDividers();positionPaneChips();
        if(!splitMode||pane.getWidth()<=0){paneFrame.setVisibility(View.GONE);previousOverlay.setTranslationX(0);nextOverlay.setTranslationX(0);previousOverlay.setTranslationY(0);nextOverlay.setTranslationY(0);return;}
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)paneFrame.getLayoutParams();
        if(lp.width!=pane.getWidth()||lp.height!=pane.getHeight()){lp.width=pane.getWidth();lp.height=pane.getHeight();paneFrame.setLayoutParams(lp);}
        View papers=(View)pane.getParent();paneFrame.setTranslationX(papers.getLeft()+pane.getLeft());paneFrame.setTranslationY(papers.getTop()+pane.getTop());paneFrame.setVisibility(View.VISIBLE);
        previousOverlay.setTranslationX(pane.getLeft());nextOverlay.setTranslationX(pane.getRight()-papers.getWidth());
        float dy=pane.getTop()+pane.getHeight()/2f-papers.getHeight()/2f;previousOverlay.setTranslationY(dy);nextOverlay.setTranslationY(dy);
    }
    private void showPenMenu(View anchor){showPenPanel(anchor,false);}
    private void showHighlightMenu(View anchor){showPenPanel(anchor,true);}
    /** A "name — slider — value" row of the pen panel. */
    private LinearLayout sliderRow(String name,int min,int max,int value,java.util.function.IntConsumer set,java.util.function.IntFunction<String> text){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(4),0,0);
        TextView label=new TextView(this);label.setText(name);label.setTextSize(13);label.setTextColor(0xFF1C1C1E);row.addView(label,new LinearLayout.LayoutParams(dp(48),-2));
        TextView number=new TextView(this);number.setTextSize(12);number.setTextColor(0xFF636366);number.setGravity(Gravity.END);number.setText(text.apply(value));
        android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(max-min);bar.setProgress(value-min);bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar b,int progress,boolean user){number.setText(text.apply(progress+min));if(user)set.accept(progress+min);}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b){}});
        row.addView(bar,new LinearLayout.LayoutParams(0,dp(32),1));row.addView(number,new LinearLayout.LayoutParams(dp(42),-2));return row;
    }
    /** A smooth sample stroke (many points, eased pressure) for the pen panel's preview and pen-type icons. */
    private AnnotationStore.InkStroke sampleStroke(int pen,int color,float width,float x0,float x1,float amp){
        AnnotationStore.InkStroke sample=new AnnotationStore.InkStroke();sample.pen=pen;sample.color=color;sample.width=width;final int n=64;
        for(int k=0;k<=n;k++){float t=k/(float)n;sample.points.add(new AnnotationStore.InkPoint(x0+(x1-x0)*t,.5f+amp*(float)Math.sin(t*Math.PI*2),.45f+.5f*(float)Math.sin(t*Math.PI)));}
        return sample;
    }
    /**
     * One tidy panel for the pen and for the ink highlighter (opened by tapping the tool again): live preview, pen type, thickness,
     * opacity, colours and the on/off options. It replaces the width / colour rows that used to hang under the tool strip.
     * The highlighter is part of the handwriting: a flat translucent band, straight or freehand. (The 하이라이트 mark tool only has a thickness menu.)
     */
    private void showPenPanel(View anchor,boolean hl){
        final float lo=hl?0.004f:0.0015f,hi=hl?0.04f:0.012f;
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(8),dp(10),dp(4));box.setTag("pen_panel");box.setContentDescription(hl?"형광펜 설정":"펜 설정");
        final View[] previewRef={null};
        final Runnable redraw=()->{if(previewRef[0]!=null)previewRef[0].invalidate();};
        TextView title=new TextView(this);title.setText(hl?"형광펜":"펜");title.setTextSize(15);title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(0xFF1C1C1E);title.setPadding(dp(2),0,0,dp(6));box.addView(title);
        View preview=new View(this){@Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);bg.setColor(0xFFF7F7FA);c.drawRoundRect(new RectF(0,0,w,h),dp(14),dp(14),bg);
            float scale=dp(420)/Math.max(1f,w);   // the sample is drawn as if the page were 420dp wide
            AnnotationStore.InkStroke sample=hl?sampleStroke(AnnotationPainter.HIGHLIGHTER,hlInkColor,hlInkWidth*scale,.08f,.92f,hlStraight?0f:.16f):sampleStroke(inkPen,inkColor,inkWidth*scale,.08f,.92f,.24f);
            AnnotationPainter.stroke(c,new RectF(0,0,w,h),sample);}};
        previewRef[0]=preview;box.addView(preview,new LinearLayout.LayoutParams(-1,dp(56)));
        if(!hl){
            box.addView(sectionLabel("펜 종류"));
            box.addView(iconSegmented(AnnotationPainter.PEN_NAMES.length,()->inkPen,i->{inkPen=i;recentPrefs.edit().putInt("ink_pen",i).apply();applyInk();updateInkButton();redraw.run();},(c,w,h,i)->
                AnnotationPainter.stroke(c,new RectF(0,0,w,h),sampleStroke(i,0xFF1C1C1E,i==4?.07f:i==3?.06f:.034f,.18f,.82f,.2f))));
            LinearLayout names=new LinearLayout(this);names.setPadding(dp(4),0,dp(4),0);
            for(String n:AnnotationPainter.PEN_NAMES){TextView t=new TextView(this);t.setText(n);t.setTextSize(11);t.setTextColor(0xFF8E8E93);t.setGravity(Gravity.CENTER);t.setSingleLine();names.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
            box.addView(names);
        }
        float current=hl?hlInkWidth:inkWidth;
        box.addView(sliderRow("굵기",1,100,Math.max(1,Math.min(100,Math.round(1+(current-lo)/(hi-lo)*99))),v->{
            float f=lo+(hi-lo)*(v-1)/99f;
            if(hl){hlInkWidth=f;recentPrefs.edit().putFloat("hl_ink_width",f).apply();}else inkWidth=f;
            applyInk();redraw.run();},v->String.valueOf(v)));
        int alpha=Color.alpha(hl?hlInkColor:inkColor);
        box.addView(sliderRow("투명도",10,100,Math.max(10,Math.round(alpha*100f/255f)),v->{
            int a=Math.max(hl?26:1,Math.round(v*255f/100f));
            if(hl)setHlInkColor((a<<24)|(hlInkColor&0xFFFFFF));else{inkColor=(a<<24)|(inkColor&0xFFFFFF);applyInk();updateInkButton();}
            redraw.run();},v->v+"%"));
        box.addView(sectionLabel("색상"));
        if(!hl){
            java.util.function.IntConsumer pick=c->{inkColor=(inkColor&0xFF000000)|(c&0xFFFFFF);applyInk();updateInkButton();redraw.run();};
            int[] palette=new int[INK_COLORS.length+INK_COLORS2.length];System.arraycopy(INK_COLORS,0,palette,0,INK_COLORS.length);System.arraycopy(INK_COLORS2,0,palette,INK_COLORS.length,INK_COLORS2.length);
            box.addView(swatches(INK_COLORS,()->inkColor|0xFF000000,pick,24,0,palette));
            box.addView(swatches(INK_COLORS2,()->inkColor|0xFF000000,pick,24,1,palette));
        }else{
            java.util.function.IntSupplier preset=()->{for(int c:HIGHLIGHT_COLORS)if((c&0xFFFFFF)==(hlInkColor&0xFFFFFF))return c;return hlInkColor;};
            box.addView(swatches(HIGHLIGHT_COLORS,preset,c->{boolean isPreset=false;for(int p:HIGHLIGHT_COLORS)if(p==c)isPreset=true;setHlInkColor(isPreset?(Color.alpha(hlInkColor)<<24)|(c&0xFFFFFF):c);redraw.run();},28,2));
        }
        if(!hl){
            box.addView(sectionLabel("옵션"));
            box.addView(switchRow("직선 · 시작점에서 끝점까지",inkMode==3,on->setInkMode(on?3:1)));
        }else{
            box.addView(sectionLabel("모양"));
            box.addView(segmented(new String[]{"직선","자유형"},()->hlStraight?0:1,i->{hlStraight=i==0;setInkMode(hlStraight?3:1);redraw.run();}));
        }
        box.addView(switchRow("손가락 필기",fingerInk,on->toggleFingerInk()));
        AnchoredMenu.show(this,anchor,true,AnchoredMenu.rows(AnchoredMenu.Row.custom(box)),null);
    }
    /** The 하이라이트 mark tool (text-following straight band): only its thickness can be set. */
    private void showMarkHighlightMenu(View anchor){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(8),dp(10),dp(4));box.setTag("pen_panel");box.setContentDescription("하이라이트 설정");
        TextView title=new TextView(this);title.setText("하이라이트");title.setTextSize(15);title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(0xFF1C1C1E);title.setPadding(dp(2),0,0,dp(6));box.addView(title);
        final View preview=new View(this){@Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);bg.setColor(0xFFF7F7FA);c.drawRoundRect(new RectF(0,0,w,h),dp(14),dp(14),bg);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(selectedColor);float band=Math.min(h-dp(8),Math.max(dp(4),highlightThick*dp(520)));c.drawRect(w*.08f,h/2f-band/2f,w*.92f,h/2f+band/2f,p);}};
        box.addView(preview,new LinearLayout.LayoutParams(-1,dp(56)));
        final float lo=0.008f,hi=0.06f;
        box.addView(sliderRow("굵기",1,100,Math.max(1,Math.min(100,Math.round(1+(highlightThick-lo)/(hi-lo)*99))),v->{highlightThick=lo+(hi-lo)*(v-1)/99f;applyHighlightStyle();preview.invalidate();},v->String.valueOf(v)));
        TextView hint=new TextView(this);hint.setText("글자를 따라 좌우로 끌어 직선으로 칠합니다. 자유롭게 그리는 형광펜은 필기 도구의 형광펜을 쓰세요.");hint.setTextSize(11);hint.setTextColor(0xFF8E8E93);hint.setPadding(dp(2),dp(6),dp(2),dp(6));box.addView(hint);
        AnchoredMenu.show(this,anchor,true,AnchoredMenu.rows(AnchoredMenu.Row.custom(box)),null);
    }
    /** Pushes the tool in use (pen or ink highlighter) to the page views. */
    private void applyInk(){
        boolean h=inkHl&&(inkMode==1||inkMode==3);
        pageView.setInkPen(h?AnnotationPainter.HIGHLIGHTER:inkPen);pageView.setInkTool(inkMode,h?hlInkColor:inkColor,h?hlInkWidth:inkWidth);syncOtherTools();
    }
    private void setHlInkColor(int color){hlInkColor=color;recentPrefs.edit().putInt("hl_ink_color",color).apply();applyInk();updateInkButton();}
    private LinearLayout switchRow(String label,boolean on,java.util.function.Consumer<Boolean> changed){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(2),dp(2),0,dp(2));
        TextView text=new TextView(this);text.setText(label);text.setTextSize(13);text.setTextColor(0xFF1C1C1E);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.Switch toggle=new android.widget.Switch(this);toggle.setChecked(on);toggle.setContentDescription(label);toggle.setOnCheckedChangeListener((b,checked)->changed.accept(checked));
        row.addView(toggle,new LinearLayout.LayoutParams(-2,-2));row.setOnClickListener(v->toggle.toggle());return row;
    }
    private float highlightThick=0.022f;
    private static final int[] ERASER_SIZES={5,10,18,28,40};
    /** Bottom-bar eraser: starts writing with the eraser; pressed again it opens the eraser range menu. */
    private void eraserBarTap(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(!writeMode)setWriteMode(true);
        eraserTap(anchor);
    }
    /** The strip keeps only the tools; width, opacity, colour and pen type live in the pen panel (showPenPanel). */
    private void updateInkOptions(){if(inkOptions!=null&&inkOptions.getVisibility()!=View.GONE){inkOptions.setVisibility(View.GONE);positionTopTools();}}
    private void eraserTap(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(inkMode==2)showEraserMenu(anchor);else setInkMode(2);
    }
    /** Eraser range: five circle sizes; the chosen radius is shown under the pen while erasing. */
    private void showEraserMenu(View anchor){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(2),dp(6),dp(2),0);
        TextView label=new TextView(this);label.setTextSize(12);label.setTextColor(0xFF8E8E93);label.setPadding(dp(10),0,dp(10),dp(2));label.setTag("eraser_size_label");
        Runnable show=()->label.setText("지우개 범위 · 지름 "+Math.round(pageView.eraserRadius()*2)+"dp");show.run();box.addView(label);
        java.util.function.IntSupplier current=()->{int best=0;for(int i=1;i<ERASER_SIZES.length;i++)if(Math.abs(ERASER_SIZES[i]-pageView.eraserRadius())<Math.abs(ERASER_SIZES[best]-pageView.eraserRadius()))best=i;return best;};
        LinearLayout sizes=iconSegmented(ERASER_SIZES.length,current,i->{setEraserRadius(ERASER_SIZES[i]);show.run();},(c,w,h,i)->{float d=getResources().getDisplayMetrics().density;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float r=Math.min(h*.42f,(3+i*3.2f)*d);p.setColor(0x22FF6B8A);c.drawCircle(w/2f,h/2f,r,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f*d);p.setColor(0xFFFF6B8A);c.drawCircle(w/2f,h/2f,r,p);});
        for(int i=0;i<sizes.getChildCount();i++)sizes.getChildAt(i).setContentDescription("지우개 크기 "+(i+1));
        box.addView(sizes);
        TextView modeLabel=new TextView(this);modeLabel.setTextSize(12);modeLabel.setTextColor(0xFF8E8E93);modeLabel.setPadding(dp(10),dp(8),dp(10),dp(2));modeLabel.setText("지우개 방식");box.addView(modeLabel);
        box.addView(segmented(new String[]{"획 지우기","부분 지우기"},()->pageView.eraserPartial()?1:0,i->setEraserPartial(i==1)));
        TextView clearAll=new TextView(this);clearAll.setText("페이지 전체 지우기");clearAll.setTextSize(14);clearAll.setTextColor(0xFFFF3B30);clearAll.setGravity(Gravity.CENTER);clearAll.setPadding(dp(10),dp(10),dp(10),dp(10));clearAll.setBackground(round(0xFFFFE3E8,14));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(dp(8),dp(8),dp(8),dp(6));
        box.addView(clearAll,cp);
        final PopupWindow[] menu=new PopupWindow[1];
        clearAll.setOnClickListener(v->{if(menu[0]!=null)menu[0].dismiss();confirmClearPage();});
        menu[0]=AnchoredMenu.show(this,anchor,true,AnchoredMenu.rows(AnchoredMenu.Row.custom(box)),null);
    }
    private void setEraserPartial(boolean partial){recentPrefs.edit().putBoolean("eraser_partial",partial).apply();for(PdfPageView v:paneViews)if(v!=null)v.setEraserPartial(partial);toast(partial?"부분 지우기 · 지우개가 닿은 부분만 지웁니다":"획 지우기 · 지우개가 닿은 획 전체를 지웁니다");}
    /** Clears all ink and highlights of the page being shown, after asking. */
    private void confirmClearPage(){
        if(renderer==null||pageView==null)return;final PdfPageView target=pageView;final int number=target.getPageNumber();
        new AlertDialog.Builder(this).setTitle("페이지 전체 지우기").setMessage((number+1)+"페이지의 모든 필기와 형광펜을 지웁니다. 계속할까요?\n(지운 직후에는 ‘실행 취소’로 되살릴 수 있습니다)")
            .setPositiveButton("전체 지우기",(d,w)->{if(target.clearPageInk()){DocumentSession ds=activeSession;if(ds!=null){ds.clearedStrokes.clear();ds.clearedStrokes.addAll(target.clearedStrokes);ds.clearedMarks.clear();ds.clearedMarks.addAll(target.clearedMarks);ds.clearedPage=number;}toast("페이지를 지웠습니다");}else toast("지울 필기가 없습니다");}).setNegativeButton("취소",null).show();
    }
    private void setEraserRadius(float dp){recentPrefs.edit().putFloat("eraser_radius",dp).apply();for(PdfPageView v:paneViews)if(v!=null)v.setEraserRadius(dp);}
    private void applyHighlightStyle(){pageView.setHighlightStyle(false,highlightThick);syncOtherTools();}
    private void showViewMenu(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        List<AnchoredMenu.Row> rows=AnchoredMenu.rows(
            new AnchoredMenu.Row("페이지 미리보기",R.drawable.ic_sidebar,this::toggleSidebar).tint(0xFF007AFF).selected(sidebarVisible),
            new AnchoredMenu.Row("두 쪽 보기",R.drawable.ic_book,this::toggleTwoPage).tint(0xFF5856D6).selected(twoPage),
            new AnchoredMenu.Row("문서 분할 보기",R.drawable.ic_sidebar,this::toggleSplit).tint(0xFFFF9500).selected(splitMode),
            new AnchoredMenu.Row("페이지로 이동",R.drawable.ic_page,this::goToPage).tint(0xFF30B0C7),
            new AnchoredMenu.Row("전체 화면",R.drawable.ic_fullscreen,this::toggleFullscreen).tint(0xFFAF52DE),
            new AnchoredMenu.Row("여백 자르기",R.drawable.ic_crop,()->{recentPrefs.edit().putBoolean("crop_margins",!cropMargins()).apply();applyCrop();toast(cropMargins()?"문서 여백을 잘라 화면에 꽉 채웁니다":"원래 여백을 그대로 보여줍니다");}).tint(0xFF34C759).selected(cropMargins()),
            new AnchoredMenu.Row("검은 문서 배경",R.drawable.ic_circle,this::toggleDarkPage).tint(0xFF3A3A3C).selected(darkPage()),
            new AnchoredMenu.Row("페이지 넘김 설정",R.drawable.ic_swipe,null).tint(0xFF8E8E93).children(choiceRows(SWIPE_CHOICES,swipeMode(),this::setSwipeMode)),
            new AnchoredMenu.Row("넘김 효과",R.drawable.ic_magic,null).tint(0xFF8E8E93).children(choiceRows(ANIM_CHOICES,pageAnimStyle(),this::setPageAnim)),
            AnchoredMenu.Row.divider(),
            new AnchoredMenu.Row("페이지 추가",R.drawable.ic_page_add,()->choosePageToInsert(currentPage)).tint(0xFF34C759),
            new AnchoredMenu.Row("다른 형식으로 페이지 추가",R.drawable.ic_page_add,()->chooseOtherPageFormat(currentPage)).tint(0xFF34C759),
            new AnchoredMenu.Row("페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(currentPage)).danger());
        AnchoredMenu.show(this,anchor,true,rows,null);
    }
    private List<AnchoredMenu.Row> libraryBackupRows(){return AnchoredMenu.rows(new AnchoredMenu.Row("모든 문서 백업",R.drawable.ic_backup,this::startLibraryBackup).tint(0xFF007AFF),new AnchoredMenu.Row("모든 문서 복원",R.drawable.ic_import,this::startLibraryRestore).tint(0xFF007AFF));}
    private List<AnchoredMenu.Row> rowsOf(int category){List<AnchoredMenu.Row> rows=new ArrayList<>();for(Tile t:categoryTiles(category)){AnchoredMenu.Row r=new AnchoredMenu.Row(t.label,t.icon,t.action).selected(t.selected);if(t.tint!=0)r.danger();rows.add(r);}return rows;}
    /** Compact top-right menu: the common actions, with the long tail grouped into sub menus. */
    private void showMainMenu(View anchor,boolean above){
        boolean doc=renderer!=null;boolean awake=recentPrefs.getBoolean("keep_awake",false);
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        rows.add(new AnchoredMenu.Row("문서 추가",R.drawable.ic_note_add,null).tint(0xFF007AFF).children(addDocumentRows()));
        rows.add(new AnchoredMenu.Row("새 노트",R.drawable.ic_compose,this::newNotebook).tint(0xFF34C759));
        if(activeSession!=null)rows.add(new AnchoredMenu.Row("이름 변경",R.drawable.ic_rename,()->renameDocument(activeSession)).tint(0xFF8E8E93));
        if(doc){
            rows.add(AnchoredMenu.Row.divider());
            rows.add(new AnchoredMenu.Row("메모·하이라이트",R.drawable.ic_highlight,this::showMarkList).tint(0xFFFF9500));
            rows.add(new AnchoredMenu.Row("책갈피 목록",R.drawable.ic_star,this::showBookmarks).tint(0xFFF5A623));
            rows.add(new AnchoredMenu.Row("번역 포스트잇",R.drawable.ic_translate,this::showTranslations).tint(0xFFAF52DE));
            rows.add(new AnchoredMenu.Row("삽입",R.drawable.ic_insert,null).children(rowsOf(2)).tint(0xFF5856D6).submenu());
            rows.add(new AnchoredMenu.Row("학습·주석",R.drawable.ic_study,null).children(rowsOf(3)).tint(0xFF30B0C7).submenu());
        }
        // whole-library backup / restore lives only in 내보내기·백업 (also offered with no document open)
        rows.add(new AnchoredMenu.Row("내보내기·백업",R.drawable.ic_share,null).children(doc?rowsOf(4):libraryBackupRows()).tint(0xFF007AFF).submenu());
        rows.add(AnchoredMenu.Row.divider());
        rows.add(new AnchoredMenu.Row("화면 켜 둠",R.drawable.ic_clock,()->{recentPrefs.edit().putBoolean("keep_awake",!awake).apply();applyKeepAwake();toast(!awake?"읽는 동안 화면이 꺼지지 않습니다":"화면 자동 꺼짐을 따릅니다");}).tint(0xFF8E8E93).selected(awake));
        rows.add(new AnchoredMenu.Row("전체 화면 메뉴 계속 표시",R.drawable.ic_float,this::toggleDockPinned).tint(0xFF8E8E93).selected(dockPinned()));
        rows.add(AnchoredMenu.Row.divider());
        rows.add(new AnchoredMenu.Row("설정",R.drawable.ic_settings,this::showSettings).tint(0xFF8E8E93));
        rows.add(new AnchoredMenu.Row("사용법",R.drawable.ic_outline,this::showHelp).tint(0xFF8E8E93));
        String pendingUpdate=pendingUpdateVersion();rows.add(new AnchoredMenu.Row(pendingUpdate==null?"앱 정보·업데이트":"앱 정보·업데이트 (새 버전 v"+pendingUpdate+")",R.drawable.ic_more_vert,this::showAbout).tint(pendingUpdate==null?0xFF8E8E93:ACCENT));
        List<AnchoredMenu.Shortcut> shortcuts=new ArrayList<>();
        shortcuts.add(new AnchoredMenu.Shortcut("문서함",R.drawable.ic_folder_open,false,this::showLibrary));
        shortcuts.add(new AnchoredMenu.Shortcut("문서·필기 검색",R.drawable.ic_search,false,this::searchDocument));
        if(doc)shortcuts.add(new AnchoredMenu.Shortcut("즐겨찾기",store.bookmarks.contains(currentPage)?R.drawable.ic_star:R.drawable.ic_star_outline,store.bookmarks.contains(currentPage),this::toggleBookmark));
        AnchoredMenu.show(this,anchor,above,rows,shortcuts);
    }
    private void showWelcome(){if(writeMode){writeMode=false;updateWriteStrip();}previousOverlay.setVisibility(View.GONE);nextOverlay.setVisibility(View.GONE);titleView.setText("Everynote");pageLabel.setText("문서 열기");}
    private void saveSessionState(){if(restoringSessions||!importing.isEmpty())return;try{JSONArray a=new JSONArray();for(DocumentSession s:sessions)a.put(new JSONObject().put("uri",s.uri.toString()).put("page",s==activeSession?currentPage:s.page).put("text_only",s.officePreview!=null));recentPrefs.edit().putString("open_sessions",a.toString()).putString("active_uri",activeSession==null?"":activeSession.uri.toString()).apply();}catch(JSONException ignored){}}
    private boolean restoreSession(){
        String raw=recentPrefs.getString("open_sessions",null);if(raw==null)return false;
        restoringSessions=true;try{JSONArray saved=new JSONArray(raw);String active=recentPrefs.getString("active_uri","");for(int i=0;i<saved.length();i++){JSONObject entry=saved.getJSONObject(i);Uri uri=Uri.parse(entry.getString("uri"));openPdf(uri,entry.optBoolean("text_only",false),Math.max(0,entry.optInt("page",0)),uri.toString().equals(active));}}catch(Exception ignored){}finally{restoringSessions=false;}
        if(importing.isEmpty())saveSessionState();return !sessions.isEmpty()||!importing.isEmpty();
    }
    private void choosePdf(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/x-hwp","application/vnd.hancom.hwp","application/vnd.hancom.hwpx","image/*","application/octet-stream"});startActivityForResult(i,OPEN_PDF);}
    private void chooseConvertedPdf(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");startActivityForResult(i,OPEN_PDF);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==EXPORT_PDF){receivePdfExport(result,data);return;}if(req==EXPORT_BACKUP){receiveBackupExport(result,data);return;}if(req==IMPORT_BACKUP){receiveBackupImport(result,data);return;}if(req==EXPORT_ORIGINAL){receiveOriginalExport(result,data);return;}if(req==IMPORT_IMAGE){if(result==RESULT_OK&&data!=null&&data.getData()!=null)importImage(data.getData());return;}if(req==IMPORT_TEMPLATE){if(result==RESULT_OK&&data!=null&&data.getData()!=null)receiveTemplate(data.getData());return;}if(req==IMPORT_VIDEO){if(result==RESULT_OK&&data!=null&&data.getData()!=null)importVideo(data.getData());return;}if(req==EXPORT_CAPTURE){receiveCaptureExport(result,data);return;}if(req==EXPORT_STUDY||req==IMPORT_SIDECAR){receiveStudyResult(req,result,data);return;}if(req==TRANSLATE_EXTERNAL){receiveExternalTranslation(result,data);return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(req==OPEN_PDF){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}openPdf(u);}else if(req==EXPORT_JSON){try(OutputStream out=getContentResolver().openOutputStream(u,"wt")){if(out==null||pendingJsonExport==null)throw new IOException("다시 백업하세요");out.write(pendingJsonExport.getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("주석을 내보냈습니다");}catch(Exception e){toast("내보내기 실패: "+e.getMessage());}}}
    private boolean isOfficeDocument(String name){String value=name.toLowerCase(Locale.ROOT);return value.endsWith(".hwp")||value.endsWith(".hwpx")||value.endsWith(".doc")||value.endsWith(".docx")||value.endsWith(".ppt")||value.endsWith(".pptx")||value.endsWith(".xls")||value.endsWith(".xlsx");}
    private boolean canConvertOffice(String name){String lower=name.toLowerCase(Locale.ROOT);return lower.endsWith(".doc")||lower.endsWith(".docx")||lower.endsWith(".ppt")||lower.endsWith(".pptx")||lower.endsWith(".xls")||lower.endsWith(".xlsx");}
    private void convertHwp(Uri source,String name){
        if(officeConverting){toast("다른 문서를 변환하고 있습니다");return;}officeConverting=true;
        TextView status=new TextView(this);status.setText("한글 문서를 준비하고 있습니다");status.setTextSize(16);status.setPadding(dp(24),dp(20),dp(24),dp(20));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("한글 문서 → PDF").setView(status).setCancelable(false).setNegativeButton("취소",null).create();dialog.show();
        hwpConversion=new HwpConversion(this,root,new HwpConversion.Callback(){
            public void status(String text){status.setText(text);}
            public void failure(String reason){hwpConversion=null;officeConverting=false;dialog.dismiss();if(!isFinishing()&&!isDestroyed()){new AlertDialog.Builder(MainActivity.this).setTitle("한글 문서 변환 실패").setMessage("PDF 변환을 완료하지 못했습니다.\n\n"+reason).setPositiveButton("다른 방법으로 열기",(d,w)->offerOfficeImport(source,name)).setNegativeButton("닫기",null).show();}}
            public void success(File pdf){
                hwpConversion=null;status.setText("문서함에 저장하는 중");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                new Thread(()->{try{Uri saved=Uri.fromFile(importConverted(pdf,name));runOnUiThread(()->{officeConverting=false;dialog.dismiss();if(!isFinishing()&&!isDestroyed()){openPdf(saved);toast("문서함에 PDF로 변환해 저장했습니다");}});}
                    catch(Exception e){runOnUiThread(()->{officeConverting=false;dialog.dismiss();if(!isFinishing()&&!isDestroyed())toast("PDF 저장 실패: "+e.getMessage());});}finally{pdf.delete();}},"hwp-save").start();
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(hwpConversion!=null)hwpConversion.cancel();hwpConversion=null;officeConverting=false;dialog.dismiss();});
        hwpConversion.start(source);
    }
    private void convertOffice(Uri source,String name){
        if(officeConverting){toast("다른 문서를 변환하고 있습니다");return;}
        if(!OfficeEngine.supported()){offerOfficeImport(source,name);return;}
        officeConverting=true;
        boolean[] finished={false};
        File[] temporary=new File[2];
        android.os.Handler conversionHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        TextView status=new TextView(this);status.setText("문서를 준비하고 있습니다");status.setPadding(dp(24),dp(20),dp(24),dp(20));status.setTextSize(16);
        AlertDialog progress=new AlertDialog.Builder(this).setTitle("PDF로 변환").setView(status).setCancelable(false).setNegativeButton("취소",null).create();progress.show();
        Runnable abort=()->{if(finished[0])return;finished[0]=true;officeConverting=false;stopService(new Intent(this,OfficeConversionService.class));progress.dismiss();for(File f:temporary)if(f!=null)f.delete();toast("변환이 중단되었습니다");};
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->abort.run());
        conversionHandler.postDelayed(abort,600000);
        new Thread(()->{
            File input=null,output=null;
            try{
                String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
                input=File.createTempFile("office-source-","."+ext,getCacheDir());
                output=File.createTempFile("office-result-",".pdf",getCacheDir());
                temporary[0]=input;temporary[1]=output;
                try(java.io.InputStream in=getContentResolver().openInputStream(source);
                    java.io.OutputStream out=new java.io.FileOutputStream(input)){
                    if(in==null)throw new IOException("파일을 읽을 수 없습니다");
                    byte[] buf=new byte[65536];int count;while((count=in.read(buf))!=-1)out.write(buf,0,count);
                }
                if(ext.equals("xls")||ext.equals("xlsx"))SpreadsheetImport.prepare(input);
                File finalInput=input,finalOutput=output;
                runOnUiThread(()->{
                    if(finished[0]||isFinishing()||isDestroyed()){finalInput.delete();finalOutput.delete();return;}
                    status.setText("원본 서식을 PDF로 변환하는 중");
                    android.os.ResultReceiver receiver=new android.os.ResultReceiver(new android.os.Handler(android.os.Looper.getMainLooper())){
                        @Override protected void onReceiveResult(int code,android.os.Bundle data){
                            if(finished[0])return;
                            if(code==2){status.setText(data.getString("stage","변환하는 중"));return;}
                            finalInput.delete();
                            if(code!=0){finished[0]=true;conversionHandler.removeCallbacks(abort);progress.dismiss();officeConverting=false;finalOutput.delete();toast("자동 변환 실패: "+data.getString("error","알 수 없는 오류"));offerOfficeImport(source,name);return;}
                            conversionHandler.removeCallbacks(abort);
                            status.setText("문서함에 저장하는 중");progress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                            new Thread(()->{
                                try{Uri saved=Uri.fromFile(importConverted(finalOutput,name));runOnUiThread(()->{finished[0]=true;conversionHandler.removeCallbacks(abort);progress.dismiss();officeConverting=false;if(!isFinishing()&&!isDestroyed()){openPdf(saved);toast("문서함에 PDF로 변환해 저장했습니다");}});}
                                catch(Exception e){runOnUiThread(()->{finished[0]=true;conversionHandler.removeCallbacks(abort);progress.dismiss();officeConverting=false;toast("PDF 저장 실패: "+e.getMessage());});}
                                finally{finalOutput.delete();}
                            },"office-save").start();
                        }
                    };
                    Intent task=new Intent(this,OfficeConversionService.class).putExtra("source",finalInput.getAbsolutePath()).putExtra("output",finalOutput.getAbsolutePath()).putExtra("receiver",receiver);
                    try{startService(task);}catch(Exception e){finished[0]=true;conversionHandler.removeCallbacks(abort);progress.dismiss();officeConverting=false;finalInput.delete();finalOutput.delete();toast("변환을 시작할 수 없습니다");offerOfficeImport(source,name);}
                });
            }catch(Exception e){
                if(input!=null)input.delete();if(output!=null)output.delete();
                String reason=e.getMessage();runOnUiThread(()->{if(finished[0])return;finished[0]=true;conversionHandler.removeCallbacks(abort);progress.dismiss();officeConverting=false;toast("자동 변환 실패: "+reason);offerOfficeImport(source,name);});
            }
        },"office-install").start();
    }
    /** Imports a converted temporary PDF straight into the app library; no separate copy is kept in Downloads. */
    private File importConverted(File pdf,String sourceName)throws Exception{
        String base=sourceName.replaceFirst("(?i)\\.(hwpx?|docx?|pptx?|xlsx?)$","").replaceAll("[\\\\/:*?\"<>|]","_");if(base.trim().isEmpty())base="문서";
        File destination=libraryFolder!=null&&libraryFolder.isDirectory()?libraryFolder:library.root;
        return library.importPdf(Uri.fromFile(pdf),base+".pdf",destination);
    }
    private void openOfficeOriginal(Uri uri,String name){
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String mime=ext.equals("hwp")?"application/x-hwp":ext.equals("hwpx")?"application/vnd.hancom.hwpx":ext.equals("doc")?"application/msword":ext.equals("docx")?"application/vnd.openxmlformats-officedocument.wordprocessingml.document":ext.equals("ppt")?"application/vnd.ms-powerpoint":"application/vnd.openxmlformats-officedocument.presentationml.presentation";
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser=Intent.createChooser(view,"원본 문서 보기");
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,new ComponentName[]{new ComponentName(this,MainActivity.class)});
        try{awaitingOfficeReturn=true;startActivity(chooser);}
        catch(ActivityNotFoundException e){awaitingOfficeReturn=false;toast("원본 형식을 열 수 있는 문서 앱이 없습니다");}
    }
    private void offerOfficeImport(Uri uri,String name){
        boolean textPreview=OfficeImporter.isOffice(name);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(name)
            .setMessage("원본 서식·표·그림은 설치된 문서 앱에서 확인할 수 있습니다. 해당 앱에서 PDF로 내보낸 뒤 다시 가져오면 Everynote에서 필기와 주석을 사용할 수 있습니다.")
            .setPositiveButton("원본 보기",(d,w)->openOfficeOriginal(uri,name))
            .setNegativeButton("PDF 가져오기",(d,w)->chooseConvertedPdf());
        if(textPreview)dialog.setNeutralButton("본문만 미리보기",(d,w)->openOfficeText(uri));
        dialog.show();
    }
    private void openOfficeText(Uri uri){openPdf(uri,true);}
    private void openPdf(Uri uri){openPdf(uri,false);}
    private void openPdf(Uri uri,boolean textOnly){openPdf(uri,textOnly,-1,true);}
    private void openPdf(Uri uri,boolean textOnly,int requestedPage,boolean activate){
        String title=queryName(uri);
        if(isOfficeDocument(title)&&!textOnly){if(title.toLowerCase(Locale.ROOT).endsWith(".hwp")||title.toLowerCase(Locale.ROOT).endsWith(".hwpx"))convertHwp(uri,title);else if(canConvertOffice(title))convertOffice(uri,title);else offerOfficeImport(uri,title);return;}
        if(!textOnly&&isPictureFile(title)&&!library.managed(uri)){convertPicture(uri,title);return;}
        if(!textOnly&&!library.managed(uri)){
            File saved=library.imported(uri);if(saved!=null){openPdf(Uri.fromFile(saved),false,requestedPage,activate);return;}
            importPdfToLibrary(uri,title,requestedPage,activate);return;
        }
        for(DocumentSession session:sessions)if(session.uri.equals(uri)){if(requestedPage>=0)session.page=Math.min(requestedPage,session.renderer.getPageCount()-1);if(activate||activeSession==null)switchDocument(session);return;}
        DocumentSession session=new DocumentSession();try{
            session.title=title;
            if(textOnly&&OfficeImporter.isOffice(title)){session.officePreview=OfficeImporter.createPreview(this,uri,title);session.descriptor=ParcelFileDescriptor.open(session.officePreview,ParcelFileDescriptor.MODE_READ_ONLY);toast("본문 글자 미리보기로 열었습니다. 표·그림·원본 서식은 반영되지 않습니다");}
            else session.descriptor="file".equals(uri.getScheme())?ParcelFileDescriptor.open(new File(uri.getPath()),ParcelFileDescriptor.MODE_READ_ONLY):getContentResolver().openFileDescriptor(uri,"r");
            if(session.descriptor==null)throw new IOException("파일을 읽을 수 없습니다");session.renderer=new PdfRenderer(session.descriptor);session.uri=uri;session.store=new AnnotationStore(this);session.store.open(uri);session.page=requestedPage<0?0:Math.min(requestedPage,session.renderer.getPageCount()-1);sessions.add(session);
            recentPrefs.edit().putString("last_uri",uri.toString()).putString("last_title",title).apply();if(activate||activeSession==null)switchDocument(session);else updateTabs();saveSessionState();
        }catch(Exception error){if(session.renderer!=null)session.renderer.close();if(session.descriptor!=null)try{session.descriptor.close();}catch(IOException ignored){}if(session.officePreview!=null)session.officePreview.delete();toast("문서 열기 실패: "+error.getMessage());if(sessions.isEmpty())showWelcome();}
    }
    private static boolean isPictureFile(String name){String v=name==null?"":name.toLowerCase(Locale.ROOT);return v.endsWith(".png")||v.endsWith(".jpg")||v.endsWith(".jpeg")||v.endsWith(".gif")||v.endsWith(".webp")||v.endsWith(".bmp")||v.endsWith(".heic")||v.endsWith(".heif");}
    /** A picture becomes a document: one PDF page in the picture's own proportions (a very tall picture is cut into A4-shaped pages), saved to the library. */
    private void convertPicture(Uri source,String title){
        if(!importing.add(source.toString()))return;File destination=libraryFolder!=null&&libraryFolder.isDirectory()?libraryFolder:library.root;
        ProgressDialog progress=ProgressDialog.show(this,"이미지 가져오기","문서로 만드는 중…",true,false);
        new Thread(()->{File temp=null;try{
            Bitmap picture;
            if(Build.VERSION.SDK_INT>=28)picture=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(),source),(decoder,info,src)->{int big=Math.max(info.getSize().getWidth(),info.getSize().getHeight());if(big>3600)decoder.setTargetSampleSize((int)Math.ceil(big/3600.0));decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);});
            else try(java.io.InputStream in=getContentResolver().openInputStream(source)){picture=BitmapFactory.decodeStream(in);}
            if(picture==null)throw new IOException("이미지를 읽을 수 없습니다");
            temp=File.createTempFile("picture",".pdf",getCacheDir());
            float aspect=picture.getHeight()/(float)picture.getWidth();int pw=aspect<1f?842:595;
            android.graphics.pdf.PdfDocument pdf=new android.graphics.pdf.PdfDocument();Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            if(aspect<=2.2f){android.graphics.pdf.PdfDocument.Page page=pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(pw,Math.max(1,Math.round(pw*aspect)),1).create());page.getCanvas().drawBitmap(picture,null,new android.graphics.Rect(0,0,pw,Math.max(1,Math.round(pw*aspect))),paint);pdf.finishPage(page);}
            else{int slice=Math.max(1,Math.round(picture.getWidth()*1.4142f)),no=1;for(int y=0;y<picture.getHeight();y+=slice,no++){int h=Math.min(slice,picture.getHeight()-y),ph=Math.max(1,Math.round(pw*h/(float)picture.getWidth()));android.graphics.pdf.PdfDocument.Page page=pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(pw,ph,no).create());page.getCanvas().drawBitmap(picture,new android.graphics.Rect(0,y,picture.getWidth(),y+h),new android.graphics.Rect(0,0,pw,ph),paint);pdf.finishPage(page);}}
            try(OutputStream out=new java.io.FileOutputStream(temp)){pdf.writeTo(out);}pdf.close();picture.recycle();
            String base=title.replaceFirst("(?i)\\.[a-z0-9]+$","").replaceAll("[\\\\/:*?\"<>|]","_");if(base.trim().isEmpty())base="이미지";
            Uri saved=Uri.fromFile(library.importPdf(Uri.fromFile(temp),base+".pdf",destination));
            runOnUiThread(()->{importing.remove(source.toString());progress.dismiss();if(!isFinishing()&&!isDestroyed()){openPdf(saved);toast("이미지를 문서로 저장했습니다");}});
        }catch(Exception|OutOfMemoryError error){runOnUiThread(()->{importing.remove(source.toString());progress.dismiss();if(!isFinishing()&&!isDestroyed())toast("이미지 가져오기 실패: "+error.getMessage());});}finally{if(temp!=null)temp.delete();}},"picture-import").start();
    }
    private void importPdfToLibrary(Uri source,String title,int page,boolean activate){
        if(!importing.add(source.toString()))return;File destination=libraryFolder!=null&&libraryFolder.isDirectory()?libraryFolder:library.root;boolean announce=!restoringSessions;
        ProgressDialog progress=announce?ProgressDialog.show(this,"PDF 가져오기","문서함에 저장하는 중…",true,false):null;
        new Thread(()->{try{File saved=library.importPdf(source,title,destination);runOnUiThread(()->{importing.remove(source.toString());if(isFinishing()||isDestroyed())return;if(progress!=null)progress.dismiss();openPdf(Uri.fromFile(saved),false,page,activate);if(announce)toast("문서함에 자동 저장했습니다");saveSessionState();});}catch(Exception error){runOnUiThread(()->{importing.remove(source.toString());if(isFinishing()||isDestroyed())return;if(progress!=null)progress.dismiss();toast("가져오기 실패: "+error.getMessage());});}},"library-import").start();
    }
    private void switchDocument(DocumentSession s){switchDocumentNow(s);consumeSplitWanted(s);}
    private void switchDocumentNow(DocumentSession s){if(splitMode){if(consumePaneWanted(s))return;splitAssign(s);return;}commitInlineText();if(searchOwner!=null&&searchOwner!=s)closeSearch();library.opened(s.uri);if(activeSession!=null)activeSession.page=currentPage;activeSession=s;renderer=s.renderer;descriptor=s.descriptor;documentUri=s.uri;documentTitle=s.title;store=s.store;titleView.setText(documentTitle);highlightMode=memoMode=outlineMode=false;inkMode=0;pageView.setLassoMode(false);pageView.stopTextSelection();pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(false);inkHl=false;applyInk();updateToolStates();updateInkButton();updateTabs();showPage(Math.min(s.page,renderer.getPageCount()-1));rebuildThumbnails();saveSessionState();}
    private void closeDocument(DocumentSession s){if(splitMode&&paneIndex(s)>=0)removePane(paneIndex(s));commitInlineText();if(s==searchOwner)closeSearch();int oldIndex=sessions.indexOf(s);sessions.remove(s);if(s.renderer!=null)s.renderer.close();if(s.descriptor!=null)try{s.descriptor.close();}catch(IOException ignored){}if(s.officePreview!=null)s.officePreview.delete();if(s==activeSession){activeSession=null;if(sessions.isEmpty()){renderer=null;descriptor=null;documentUri=null;store=null;firstPageView.clearPage();secondPageView.clearPage();thumbnailList.removeAllViews();refreshStudyPanel();updateTabs();showWelcome();}else switchDocument(sessions.get(Math.max(0,Math.min(oldIndex,sessions.size()-1))));}else updateTabs();saveSessionState();}
    private void updateTabs(){
        updateSplitBar();tabRow.removeAllViews();View activeTab=null;
        for(DocumentSession session:sessions){
            boolean active=session==activeSession,paired=splitMode&&paneIndex(session)>=0;LinearLayout chip=new LinearLayout(this);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(8),0,dp(0),0);chip.setTag("document_tab");
            GradientDrawable background=round(active?Color.WHITE:0xFFF2F2F7,14);background.setStroke(dp(1),active?0xFFC7C7CC:paired?ACCENT:0xFFE5E5EA);chip.setBackground(background);chip.setElevation(active?dp(2):0);
            ImageView documentIcon=new ImageView(this);documentIcon.setImageResource(R.drawable.ic_document_tab);documentIcon.setColorFilter(active?ACCENT:0xFFAEAEB2);chip.addView(documentIcon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView name=new TextView(this){@Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);scrollTo(0,0);}};
            name.setTag("document_tab_title");name.setText(session.title);name.setSingleLine();name.setHorizontallyScrolling(false);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);name.setTextDirection(View.TEXT_DIRECTION_LTR);name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);name.setTextColor(active?NAVY:0xFF8E8E93);name.setTextSize(12);name.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);name.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);name.setIncludeFontPadding(false);name.setPadding(dp(6),0,0,0);name.setOnClickListener(v->switchDocument(session));name.setOnLongClickListener(v->{renameDocument(session);return true;});chip.addView(name,new LinearLayout.LayoutParams(dp(96),dp(30)));
            TextView close=new TextView(this);close.setText("×");close.setGravity(Gravity.CENTER);close.setTextSize(19);close.setTextColor(0xFFAEAEB2);close.setContentDescription(session.title+" 닫기");close.setOnClickListener(v->closeDocument(session));chip.addView(close,new LinearLayout.LayoutParams(dp(26),dp(30)));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(30));cp.setMargins(dp(3),0,dp(3),0);tabRow.addView(chip,cp);if(active)activeTab=chip;
        }
        ImageView add=new ImageView(this);add.setImageResource(R.drawable.ic_plus);add.setColorFilter(ACCENT);add.setScaleType(ImageView.ScaleType.FIT_CENTER);add.setPadding(dp(7),dp(7),dp(7),dp(7));add.setContentDescription("문서 추가");add.setBackground(round(0xFFE5E5EA,15));add.setOnClickListener(v->showAddDocumentMenu());LinearLayout.LayoutParams plus=new LinearLayout.LayoutParams(dp(30),dp(30));plus.setMargins(dp(4),0,dp(8),0);tabRow.addView(add,plus);
        final View selected=activeTab;if(selected!=null)tabStrip.post(()->tabStrip.smoothScrollTo(Math.max(0,selected.getLeft()-dp(8)),0));
    }
    private String queryName(Uri u){try(android.database.Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}}catch(Exception ignored){}return u.getLastPathSegment()==null?"PDF":u.getLastPathSegment();}
    private void toggleSidebar(){if(sidebarVisible&&panelTab==1)closeSidePanel();else selectPanelTab(1);}
    private void updateThumbnailSelection(){for(int i=0;i<thumbnailList.getChildCount();i++){View v=thumbnailList.getChildAt(i);Object tag=v.getTag();if(!(tag instanceof Integer))continue;boolean selected=((Integer)tag)==currentPage;GradientDrawable bg=round(selected?0xFFE5F0FF:Color.TRANSPARENT,8);if(selected)bg.setStroke(dp(2),ACCENT);v.setBackground(bg);}View selected=thumbnailList.findViewWithTag(currentPage);if(sidebarVisible&&selected!=null)selected.post(()->thumbnailPanel.smoothScrollTo(0,Math.max(0,selected.getTop()-dp(16))));}
    private Bitmap renderPage(PdfRenderer target,int index){try(PdfRenderer.Page page=target.openPage(index)){int width=Math.max(1080,getResources().getDisplayMetrics().widthPixels*(twoPage?1:2));float ratio=Math.min(2.5f,(float)width/page.getWidth());Bitmap image=Bitmap.createBitmap(Math.max(1,(int)(page.getWidth()*ratio)),Math.max(1,(int)(page.getHeight()*ratio)),Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);Matrix matrix=new Matrix();matrix.postScale(ratio,ratio);page.render(image,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return image;}}
    private void showPage(int index){
        commitInlineText();onSelectionAdjustStarted();stopInlinePlayer();if(renderer==null||index<0||index>=renderer.getPageCount())return;++ocrGeneration;resetPageTransforms();
        if(splitMode){showSplitPane(index);return;}
        int first=twoPage?(index/2)*2:index;firstPageView.showPage(renderPage(renderer,first),first,store.marks,store.strokes,store.translations);firstPageView.setAnnotationStore(store);
        if(twoPage&&first+1<renderer.getPageCount()){secondPageView.setVisibility(View.VISIBLE);secondPageView.showPage(renderPage(renderer,first+1),first+1,store.marks,store.strokes,store.translations);secondPageView.setAnnotationStore(store);}else{secondPageView.clearPage();secondPageView.setVisibility(twoPage?View.INVISIBLE:View.GONE);}
        boolean spread=twoPage&&secondPageView.getVisibility()==View.VISIBLE;firstPageView.setSpread(-1,spread?secondPageView:null);secondPageView.setSpread(1,spread?firstPageView:null);
        pageView=twoPage&&index!=first?secondPageView:firstPageView;currentPage=index;activeSession.page=index;updateZoomLabel(pageView.zoom());syncOtherTools();
        previousOverlay.setVisibility(first>0?View.VISIBLE:View.GONE);nextOverlay.setVisibility(first+(twoPage?2:1)<renderer.getPageCount()||isNotebook(activeSession)?View.VISIBLE:View.GONE);nextOverlay.setContentDescription(first+(twoPage?2:1)>=renderer.getPageCount()&&isNotebook(activeSession)?"새 페이지 추가":"다음 페이지");
        pageLabel.setText(twoPage?(first+1)+"–"+Math.min(first+2,renderer.getPageCount())+" / "+renderer.getPageCount():(index+1)+" / "+renderer.getPageCount());
        applyCrop();   // setCrop resets the pan, so the carried zoom is restored only after it
        if(carryScale>0f){firstPageView.restoreView(carryScale,carryPanX,carryPanY);if(secondPageView.getVisibility()==View.VISIBLE)secondPageView.restoreView(carryScale,carryPanX,carryPanY);updateZoomLabel(pageView.zoom());}carryScale=-1f;
        updateBookmarkButton();updateThumbnailSelection();refreshStudyPanel();saveSessionState();applySearchHighlights();
        loadViewText(firstPageView);if(twoPage&&secondPageView.getVisibility()==View.VISIBLE)loadViewText(secondPageView);
    }
    private void loadViewText(PdfPageView view){List<PdfPageView.TextRegion> cached=activeSession.textRegions.get(view.getPageNumber());if(cached!=null)view.setTextRegions(cached,false);else view.post(()->recognizeViewText(view,false));}
    private void syncOtherTools(){if(pageView==null)return;for(PdfPageView v:paneViews)if(v!=null&&v!=pageView)v.copyToolsFrom(pageView);}
    // ---------------------------------------------------------------- split view: 2 to 4 open documents at once (left/right, top/bottom, 2x2)
    // Page views are slot-fixed: paneViews[i] is the i-th pane on screen and shows paneSessions[i]. The pane touched last is the active one
    // (pageView / activeSession / ... follow it). splitLayout: 0 = side by side, 1 = stacked, 2 = grid (3 panes: one wide on top and two below, 4 panes: 2 x 2).
    private static final int MAX_PANES=4;
    private int paneIndex(PdfPageView v){for(int i=0;i<paneCount;i++)if(paneViews[i]==v)return i;return -1;}
    private int paneIndex(DocumentSession s){for(int i=0;i<paneCount;i++)if(paneSessions[i]==s)return i;return -1;}
    private int activePane(){int i=paneIndex(pageView);return i<0?0:i;}
    private int clampPage(DocumentSession s){return Math.max(0,Math.min(s.page,s.renderer.getPageCount()-1));}
    private int normLayout(int layout,int n){return layout==1?1:(layout==2&&n>=3?2:0);}
    private int trackCols(int layout,int n){return layout==1?1:layout==2?2:n;}
    private int trackRows(int layout,int n){return layout==1?n:layout==2?2:1;}
    /** {column, row, column span} of pane i (0-based) */
    private int[] paneCell(int layout,int n,int i){
        if(layout==1)return new int[]{0,i,1};
        if(layout==2)return n==3?(i==0?new int[]{0,0,2}:new int[]{i-1,1,1}):new int[]{i%2,i/2,1};
        return new int[]{i,0,1};
    }
    private float[] equalSizes(int k){float[] a=new float[k];Arrays.fill(a,1f/k);return a;}
    private float[] sizePair(float r){r=Math.min(.85f,Math.max(.15f,r));return new float[]{r,1-r};}
    private void resetSplitSizes(){
        int c=trackCols(splitLayout,paneCount),r=trackRows(splitLayout,paneCount);
        splitCols=c==2?sizePair(recentPrefs.getFloat("split_ratio",.5f)):equalSizes(c);splitRows=r==2?sizePair(recentPrefs.getFloat("split_vratio",.5f)):equalSizes(r);
    }
    /** {start,end} pixel pairs of the tracks along a length with 4 dp gaps between them */
    private int[] trackEdges(float[] sizes,int len){
        int gap=dp(4),k=sizes.length;float total=Math.max(0,len-gap*(k-1)),pos=0;int[] e=new int[k*2];
        for(int i=0;i<k;i++){e[i*2]=Math.round(pos);pos+=sizes[i]*total;e[i*2+1]=i==k-1?len:Math.round(pos);pos+=gap;}
        return e;
    }
    /** {left,top,right,bottom} of pane i inside the papers area of the given size */
    private int[] paneRect(int i,int w,int h){
        if(i>=paneCount)return new int[]{0,0,0,0};
        int[] cell=paneCell(splitLayout,paneCount,i),ce=trackEdges(splitCols,w),re=trackEdges(splitRows,h);int last=cell[0]+cell[2]-1;
        return new int[]{ce[cell[0]*2],re[cell[1]*2],ce[last*2+1],re[cell[1]*2+1]};
    }
    private PdfPageView paneAt(float x,float y){
        for(int i=0;i<paneCount;i++){PdfPageView v=paneViews[i];if(v.getVisibility()==View.VISIBLE&&x>=v.getLeft()&&x<v.getRight()&&y>=v.getTop()&&y<v.getBottom())return v;}
        return null;
    }
    /** Split view: open documents side by side; the pane touched last is the active one (its document, page and tools drive the toolbar). */
    private void toggleSplit(){
        if(splitMode){exitSplit();toast("분할 보기를 종료했습니다");return;}
        if(activeSession==null||renderer==null){toast("문서를 먼저 여세요");return;}
        List<DocumentSession> others=new ArrayList<>(sessions);others.remove(activeSession);
        if(others.isEmpty()){
            new AlertDialog.Builder(this).setTitle("분할 보기").setMessage("화면을 나누려면 문서가 두 개 필요합니다. 두 번째 문서를 열까요?\n(열면 지금 문서와 나란히 표시됩니다. 화면은 최대 4개까지, 좌우·상하·2×2로 나눌 수 있습니다)")
                .setPositiveButton("문서 열기",(d,w)->{splitWantedSession=activeSession;splitWantedAt=SystemClock.elapsedRealtime();showAddDocumentMenu();}).setNegativeButton("취소",null).show();
            return;
        }
        if(others.size()==1){enterSplit(others.get(0));return;}
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        for(DocumentSession o:others)rows.add(new AnchoredMenu.Row(o.title,R.drawable.ic_document_tab,()->enterSplit(o)).tint(0xFF007AFF));
        AnchoredMenu.showCentered(this,getWindow().getDecorView(),"두 번째 문서 선택",rows);
    }
    /** Shows {@code other} (an open document that is not the active one) in a second pane next to the active document. */
    private void enterSplit(DocumentSession other){
        if(splitMode||activeSession==null||other==null||other==activeSession||!sessions.contains(other))return;
        twoPageBeforeSplit=twoPage;twoPage=false;splitMode=true;paneCount=2;Arrays.fill(paneSessions,null);paneSessions[0]=activeSession;paneSessions[1]=other;
        pageView=firstPageView;splitLayout=normLayout(recentPrefs.getInt("split_layout",0),2);resetSplitSizes();
        secondPageView.setVisibility(View.VISIBLE);applySplitLayout();
        showPaneView(secondPageView,other,clampPage(other));
        showPage(currentPage);updateTabs();toast("분할 보기 · 필기할 화면을 터치하고, 화면 위쪽 문서 이름을 눌러 문서·배치를 바꿉니다");
    }
    /** Finishes "split with a newly opened document" (the old document goes left, the new one right). */
    private void consumeSplitWanted(DocumentSession opened){
        DocumentSession wanted=splitWantedSession;if(wanted==null)return;splitWantedSession=null;
        if(SystemClock.elapsedRealtime()-splitWantedAt<3*60*1000L&&wanted!=opened&&sessions.contains(wanted)&&!splitMode&&opened==activeSession){enterSplit(wanted);swapSplitPanes(0,1);}
    }
    /** A document opened right after "화면 추가" becomes a new pane instead of replacing the active one. */
    private boolean consumePaneWanted(DocumentSession opened){
        if(!paneWanted)return false;paneWanted=false;
        if(SystemClock.elapsedRealtime()-paneWantedAt<3*60*1000L&&splitMode&&paneCount<MAX_PANES&&paneIndex(opened)<0&&sessions.contains(opened)){addPane(opened,true);return true;}
        return false;
    }
    /** Adds a pane (2 -> 3 -> 4) showing {@code s}; {@code activate} makes it the pane that is written on. */
    private void addPane(DocumentSession s,boolean activate){
        if(!splitMode||paneCount>=MAX_PANES||s==null||paneIndex(s)>=0||!sessions.contains(s))return;
        commitInlineText();if(activeSession!=null)activeSession.page=currentPage;
        PdfPageView v=paneViews[paneCount];paneSessions[paneCount]=s;paneCount++;
        splitLayout=normLayout(splitLayout,paneCount);resetSplitSizes();
        v.copyToolsFrom(pageView);v.setZoom(1f);v.setVisibility(View.VISIBLE);applySplitLayout();
        showPaneView(v,s,clampPage(s));
        if(activate)splitFocusView(v);
        updateTabs();updateSplitChrome();
    }
    private void promptAddPane(){
        if(!splitMode||paneCount>=MAX_PANES){toast("화면은 최대 4개까지 나눌 수 있습니다");return;}
        List<DocumentSession> free=new ArrayList<>();for(DocumentSession o:sessions)if(paneIndex(o)<0)free.add(o);
        if(free.isEmpty()){
            new AlertDialog.Builder(this).setTitle("화면 추가").setMessage("추가로 보여 줄 문서가 없습니다. 새 문서를 열까요?")
                .setPositiveButton("문서 열기",(d,w)->{paneWanted=true;paneWantedAt=SystemClock.elapsedRealtime();showAddDocumentMenu();}).setNegativeButton("취소",null).show();
            return;
        }
        if(free.size()==1){addPane(free.get(0),true);return;}
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        for(DocumentSession o:free)rows.add(new AnchoredMenu.Row(o.title,R.drawable.ic_document_tab,()->addPane(o,true)).tint(0xFF007AFF));
        AnchoredMenu.showCentered(this,getWindow().getDecorView(),"추가할 문서 선택",rows);
    }
    /** Takes pane k off the screen; the panes after it move up one place. With two panes left the split ends instead. */
    private void removePane(int k){
        if(!splitMode||k<0||k>=paneCount)return;
        if(paneCount<=2){if(k==activePane())splitFocusView(paneViews[k==0?1:0]);exitSplit();return;}
        commitInlineText();
        if(paneSessions[k]==activeSession)splitFocusView(paneViews[k>0?k-1:1]);
        DocumentSession act=activeSession;act.page=currentPage;
        for(int j=k;j<paneCount-1;j++){paneSessions[j]=paneSessions[j+1];showPaneView(paneViews[j],paneSessions[j],clampPage(paneSessions[j]));paneViews[j].setZoom(1f);}
        PdfPageView last=paneViews[paneCount-1];last.clearPage();last.setVisibility(View.GONE);paneSessions[paneCount-1]=null;paneCount--;
        splitLayout=normLayout(splitLayout,paneCount);resetSplitSizes();
        int a=paneIndex(act);pageView=paneViews[a<0?0:a];currentPage=pageView.getPageNumber();syncOtherTools();updateZoomLabel(pageView.zoom());
        applySplitLayout();updateTabs();
    }
    /** Exchanges the positions of two panes (the documents move with them; the active document stays active). */
    private void swapSplitPanes(int a,int b){
        if(!splitMode||a==b||a<0||b<0||a>=paneCount||b>=paneCount)return;
        commitInlineText();if(activeSession!=null)activeSession.page=currentPage;
        DocumentSession act=activeSession,t=paneSessions[a];paneSessions[a]=paneSessions[b];paneSessions[b]=t;
        for(int i:new int[]{a,b}){showPaneView(paneViews[i],paneSessions[i],clampPage(paneSessions[i]));paneViews[i].setZoom(1f);}
        int k=paneIndex(act);pageView=paneViews[k<0?0:k];currentPage=pageView.getPageNumber();syncOtherTools();updateZoomLabel(pageView.zoom());updateTabs();updateSplitChrome();
    }
    private void setSplitLayoutMode(int layout){
        if(!splitMode)return;splitLayout=normLayout(layout,paneCount);recentPrefs.edit().putInt("split_layout",splitLayout).apply();resetSplitSizes();applySplitLayout();
    }
    /** Shows / hides the pane views, switches the papers area to the pane layout, and rebuilds dividers and name chips. */
    private void applySplitLayout(){
        if(papersLayout==null)return;
        if(splitMode){
            splitLayout=normLayout(splitLayout,paneCount);
            if(splitCols.length!=trackCols(splitLayout,paneCount)||splitRows.length!=trackRows(splitLayout,paneCount))resetSplitSizes();
            for(int i=0;i<4;i++)paneViews[i].setVisibility(i<paneCount?View.VISIBLE:View.GONE);
        }
        papersLayout.grid=splitMode;papersLayout.setBackgroundColor(splitMode?0xFF8E8E93:Color.TRANSPARENT);papersLayout.requestLayout();
        buildSplitDividers();updateSplitBar();
    }
    private final class SplitDivider extends View{
        final boolean horizontal;final int index;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float downPos;long lastTap;boolean moved;
        SplitDivider(boolean horizontal,int index){super(MainActivity.this);this.horizontal=horizontal;this.index=index;
            setTag(horizontal?"split_divider_h":"split_divider");setContentDescription(horizontal?"화면 높이 조절 · 위아래로 끌기, 두 번 누르면 같은 크기로":"화면 너비 조절 · 좌우로 끌기, 두 번 누르면 같은 크기로");}
        @Override protected void onDraw(Canvas c){
            float d=getResources().getDisplayMetrics().density;float gw=horizontal?44*d:6*d,gh=horizontal?6*d:44*d;
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);RectF r=new RectF(getWidth()/2f-gw/2,getHeight()/2f-gh/2,getWidth()/2f+gw/2,getHeight()/2f+gh/2);c.drawRoundRect(r,3*d,3*d,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(0xFF8E8E93);c.drawRoundRect(r,3*d,3*d,p);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(papersView==null||papersView.getWidth()<=0)return true;
            float[] sizes=horizontal?splitRows:splitCols;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:downPos=horizontal?e.getRawY():e.getRawX();moved=false;getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE:{
                    float raw=horizontal?e.getRawY():e.getRawX();if(Math.abs(raw-downPos)>dp(6))moved=true;
                    int[] loc=new int[2];papersView.getLocationOnScreen(loc);float len=horizontal?papersView.getHeight():papersView.getWidth();
                    float pos=(raw-(horizontal?loc[1]:loc[0]))/len,before=0;for(int j=0;j<index;j++)before+=sizes[j];
                    float pair=sizes[index]+sizes[index+1],m=Math.min(.15f,pair/2),q=Math.min(before+pair-m,Math.max(before+m,pos));
                    sizes[index]=q-before;sizes[index+1]=pair-sizes[index];papersLayout.requestLayout();return true;}
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:{
                    if(!moved&&e.getActionMasked()==MotionEvent.ACTION_UP){long now=SystemClock.elapsedRealtime();if(now-lastTap<400){float[] eq=equalSizes(sizes.length);System.arraycopy(eq,0,sizes,0,sizes.length);papersLayout.requestLayout();lastTap=0;}else lastTap=now;}
                    if(sizes.length==2)recentPrefs.edit().putFloat(horizontal?"split_vratio":"split_ratio",sizes[0]).apply();return true;}
            }
            return true;
        }
    }
    private void buildSplitDividers(){
        if(viewportLayer==null)return;
        for(View d:splitDividers)viewportLayer.removeView(d);splitDividers.clear();
        if(!splitMode)return;
        for(int i=0;i<splitCols.length-1;i++)splitDividers.add(new SplitDivider(false,i));
        for(int i=0;i<splitRows.length-1;i++)splitDividers.add(new SplitDivider(true,i));
        for(View d:splitDividers){d.setVisibility(View.GONE);viewportLayer.addView(d,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.TOP|Gravity.START));}
        positionSplitDividers();
    }
    /** Each grip sits on its seam, centred along it (in a 2 x 2 grid the two grips are moved apart so they never overlap). */
    private void positionSplitDividers(){
        if(papersView==null)return;boolean show=splitMode&&papersView.getWidth()>0;
        int w=papersView.getWidth(),h=papersView.getHeight();int[] ce=trackEdges(splitCols,w),re=trackEdges(splitRows,h);
        boolean both=splitCols.length>1&&splitRows.length>1,topWide=splitLayout==2&&paneCount==3;
        for(View view:splitDividers){
            SplitDivider d=(SplitDivider)view;if(!show){d.setVisibility(View.GONE);continue;}
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)d.getLayoutParams();int cx,cy;
            if(!d.horizontal){
                cx=(ce[d.index*2+1]+ce[(d.index+1)*2])/2;
                if(topWide)cy=(re[2]+re[3])/2;else if(both)cy=(re[0]+re[1])/2;else cy=h/2;
                lp.width=dp(28);lp.height=dp(64);
            }else{
                cy=(re[d.index*2+1]+re[(d.index+1)*2])/2;
                cx=both&&!topWide?(ce[0]+ce[1])/2:w/2;
                lp.width=dp(64);lp.height=dp(28);
            }
            int x=papersView.getLeft()+cx-lp.width/2,y=papersView.getTop()+cy-lp.height/2;
            if(lp.leftMargin!=x||lp.topMargin!=y){lp.leftMargin=x;lp.topMargin=y;d.setLayoutParams(lp);}
            d.setVisibility(View.VISIBLE);
        }
    }
    private void buildSplitBar(FrameLayout viewport){
        // like the Windows app: each split screen carries its own menu chip (document name ▾) in its top-left corner, floating over the page — no separate bar
        splitBar=new FrameLayout(this);splitBar.setTag("split_bar");splitBar.setVisibility(View.GONE);
        for(int i=0;i<4;i++){
            final int k=i;TextView chip=new TextView(this);chip.setTag("split_pane_chip");chip.setSingleLine();chip.setEllipsize(android.text.TextUtils.TruncateAt.END);chip.setGravity(Gravity.CENTER);chip.setTextSize(12.5f);chip.setPadding(dp(11),0,dp(11),0);chip.setElevation(dp(3));
            chip.setVisibility(View.GONE);chip.setOnClickListener(v->showPaneMenu(v,k));paneChip[i]=chip;splitBar.addView(chip,new FrameLayout.LayoutParams(-2,dp(28),Gravity.START|Gravity.TOP));
        }
        viewport.addView(splitBar,new FrameLayout.LayoutParams(-1,-1));
    }
    /** Pane title chips (pick a document / layout / add / close / end) and the split toggle state. */
    private void updateSplitBar(){
        if(splitBar==null)return;boolean on=splitMode&&paneCount>=2;
        splitBar.setVisibility(on?View.VISIBLE:View.GONE);
        if(papersView!=null){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)papersView.getLayoutParams();if(lp.topMargin!=0){lp.topMargin=0;papersView.setLayoutParams(lp);}}
        if(splitButton!=null)splitButton.setBackground(round(on?0xFFE4E4FA:Color.TRANSPARENT,22));
        for(int i=0;i<4;i++){
            TextView chip=paneChip[i];boolean show=on&&i<paneCount;chip.setVisibility(show?View.VISIBLE:View.GONE);if(!show)continue;
            DocumentSession s=paneSessions[i];boolean active=s==activeSession;chip.setText(s.title+" ▾");chip.setContentDescription("화면 문서: "+s.title);
            chip.setTextColor(active?ACCENT:0xFF636366);chip.setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
            GradientDrawable bg=round(active?Color.WHITE:0xFFEDEDF0,12);bg.setStroke(dp(active?2:1),active?ACCENT:0xFFD1D1D6);chip.setBackground(bg);
        }
        updateSplitChrome();
    }
    private boolean chipsRepositionQueued;
    /** Chips are sized to their pane; the panes may still have their old size right after a layout change, so measure again on the next frame. */
    private void positionPaneChips(){
        positionPaneChipsNow();
        if(!chipsRepositionQueued&&papersView!=null){chipsRepositionQueued=true;papersView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){@Override public void onGlobalLayout(){ViewTreeObserver o=papersView.getViewTreeObserver();if(o.isAlive())o.removeOnGlobalLayoutListener(this);chipsRepositionQueued=false;positionPaneChipsNow();}});}
    }
    private void positionPaneChipsNow(){
        if(!splitMode||papersView==null||papersView.getWidth()<=0)return;
        for(int i=0;i<paneCount;i++){PdfPageView p=paneViews[i];TextView chip=paneChip[i];int room=Math.max(dp(60),p.getWidth()-dp(16));if(chip.getMaxWidth()!=room)chip.setMaxWidth(room);chip.setTranslationX(papersView.getLeft()+p.getLeft()+dp(8));chip.setTranslationY(papersView.getTop()+p.getTop()+dp(6));}
    }
    private String layoutLabel(int layout,int n){return layout==0?(n==2?"좌우로 나누기":"가로로 "+n+"등분"):layout==1?(n==2?"상하로 나누기":"세로로 "+n+"등분"):(n==3?"위 1칸 · 아래 2칸":"2 × 2 (4분할)");}
    /** Menu of pane k: its document, the layout, add / close / swap panes, end split. */
    private void showPaneMenu(View anchor,int k){
        if(!splitMode||k>=paneCount)return;DocumentSession mine=paneSessions[k];List<AnchoredMenu.Row> rows=new ArrayList<>();
        for(DocumentSession o:sessions)rows.add(new AnchoredMenu.Row(o.title,R.drawable.ic_document_tab,()->assignPaneDocument(k,o)).tint(0xFF007AFF).selected(o==mine));
        rows.add(AnchoredMenu.Row.divider());
        for(int L=0;L<3;L++){if(L==2&&paneCount<3)continue;final int layout=L;rows.add(new AnchoredMenu.Row(layoutLabel(L,paneCount),R.drawable.ic_dual,()->setSplitLayoutMode(layout)).tint(0xFF5856D6).selected(splitLayout==L));}
        if(paneCount<MAX_PANES)rows.add(new AnchoredMenu.Row("화면 추가 ("+paneCount+"/"+MAX_PANES+")",R.drawable.ic_page_add,this::promptAddPane).tint(0xFF34C759));
        if(paneCount>2)rows.add(new AnchoredMenu.Row("이 화면 닫기",R.drawable.ic_close,()->removePane(k)).tint(0xFFFF9500));
        for(int j=0;j<paneCount;j++){if(j==k)continue;final int other=j;rows.add(new AnchoredMenu.Row(paneCount==2?"위치 바꾸기":"위치 바꾸기 · "+paneSessions[j].title,R.drawable.ic_swipe,()->swapSplitPanes(k,other)).tint(0xFF5856D6));}
        rows.add(new AnchoredMenu.Row("다른 문서 열기",R.drawable.ic_folder_open,()->{splitFocusView(paneViews[k]);showAddDocumentMenu();}).tint(0xFF8E8E93));
        rows.add(new AnchoredMenu.Row("분할 보기 끝내기",R.drawable.ic_close,()->{exitSplit();toast("분할 보기를 종료했습니다");}).danger());
        AnchoredMenu.show(this,anchor,false,rows,null);
    }
    private void assignPaneDocument(int k,DocumentSession s){
        if(!splitMode||k>=paneCount)return;DocumentSession mine=paneSessions[k];
        if(s==mine){splitFocusView(paneViews[k]);return;}
        int j=paneIndex(s);if(j>=0){swapSplitPanes(k,j);return;}
        splitFocusView(paneViews[k]);splitAssign(s);
    }
    private void exitSplit(){
        if(!splitMode)return;DocumentSession keep=activeSession;splitMode=false;twoPage=twoPageBeforeSplit;paneCount=0;Arrays.fill(paneSessions,null);
        papersLayout.grid=false;papersLayout.setBackgroundColor(Color.TRANSPARENT);
        // both halves go back to equal width and to "spread" (seam-aligned) drawing, whatever width the divider had and whichever pane was touched last
        for(PdfPageView v:new PdfPageView[]{firstPageView,secondPageView}){v.resetPaneState();LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)v.getLayoutParams();lp.weight=1;lp.leftMargin=0;v.setLayoutParams(lp);}
        for(int i=2;i<4;i++){paneViews[i].clearPage();paneViews[i].resetPaneState();paneViews[i].setVisibility(View.GONE);}
        secondPageView.clearPage();secondPageView.setVisibility(twoPage?View.INVISIBLE:View.GONE);buildSplitDividers();updateSplitBar();papersLayout.requestLayout();
        if(keep!=null&&renderer!=null){pageView=firstPageView;showPage(keep.page<0?0:Math.min(keep.page,renderer.getPageCount()-1));}
        updateTabs();updateSplitChrome();
        papersView.post(()->{if(splitMode||renderer==null||!twoPage||secondPageView.getVisibility()!=View.VISIBLE)return;firstPageView.setSpread(-1,secondPageView);secondPageView.setSpread(1,firstPageView);firstPageView.invalidate();secondPageView.invalidate();});
    }
    /** Draws one document page into the given pane without touching the active-document state. */
    private void showPaneView(PdfPageView v,DocumentSession s,int index){
        v.showPage(renderPage(s.renderer,index),index,s.store.marks,s.store.strokes,s.store.translations);v.setAnnotationStore(s.store);v.setSpread(v==firstPageView?-1:1,null);s.page=index;
    }
    private void showSplitPane(int index){
        int k=paneIndex(activeSession);PdfPageView v=paneViews[k<0?0:k];
        showPaneView(v,activeSession,index);pageView=v;currentPage=index;syncOtherTools();updateZoomLabel(v.zoom());
        previousOverlay.setVisibility(index>0?View.VISIBLE:View.GONE);nextOverlay.setVisibility(index+1<renderer.getPageCount()||isNotebook(activeSession)?View.VISIBLE:View.GONE);
        pageLabel.setText((index+1)+" / "+renderer.getPageCount());updateSplitChrome();loadViewText(v);updateBookmarkButton();updateThumbnailSelection();refreshStudyPanel();saveSessionState();applySearchHighlights();
    }
    /** Makes the document shown in the touched pane the active one (document, page, store) without resetting the tools. */
    private void splitFocusView(PdfPageView v){splitFocusImpl(v);updateSplitChrome();}
    private void splitFocusImpl(PdfPageView v){
        int k=paneIndex(v);if(k<0||v.getVisibility()!=View.VISIBLE)return;DocumentSession s=paneSessions[k];if(s==null)return;
        if(s==activeSession){pageView=v;currentPage=v.getPageNumber();return;}
        commitInlineText();if(searchOwner!=null&&searchOwner!=s)closeSearch();activeSession.page=currentPage;
        activeSession=s;renderer=s.renderer;descriptor=s.descriptor;documentUri=s.uri;documentTitle=s.title;store=s.store;titleView.setText(documentTitle);
        pageView=v;currentPage=v.getPageNumber();s.page=currentPage;syncOtherTools();updateZoomLabel(v.zoom());
        pageLabel.setText((currentPage+1)+" / "+renderer.getPageCount());previousOverlay.setVisibility(currentPage>0?View.VISIBLE:View.GONE);nextOverlay.setVisibility(currentPage+1<renderer.getPageCount()||isNotebook(s)?View.VISIBLE:View.GONE);
        updateTabs();updateBookmarkButton();rebuildThumbnails();refreshStudyPanel();loadViewText(v);saveSessionState();
    }
    /** In split view a tab shows its document in the active pane (or just focuses the pane that already shows it). */
    private void splitAssign(DocumentSession s){
        int k=paneIndex(s);if(k>=0){splitFocusView(paneViews[k]);return;}
        commitInlineText();int a=paneIndex(pageView);if(a<0)a=0;
        paneSessions[a]=s;
        activeSession=s;renderer=s.renderer;descriptor=s.descriptor;documentUri=s.uri;documentTitle=s.title;store=s.store;titleView.setText(documentTitle);library.opened(s.uri);
        showPage(Math.max(0,Math.min(s.page,renderer.getPageCount()-1)));rebuildThumbnails();updateTabs();
    }
    private void toggleTwoPage(){if(splitMode){toast("분할 보기를 끄고 두 쪽 보기를 사용하세요");return;}twoPage=!twoPage;recentPrefs.edit().putBoolean("two_page",twoPage).apply();if(renderer!=null)showPage(currentPage);else secondPageView.setVisibility(twoPage?View.INVISIBLE:View.GONE);toast(twoPage?"두 쪽 보기 · 각 페이지를 터치해 필기하세요":"한 쪽 보기");}
    private void toggleHighlight(){if(renderer==null)return;highlightMode=!highlightMode;memoMode=outlineMode=false;stopInk();updateToolStates();pageView.setMemoMode(false);pageView.setOutlineMode(false);pageView.setHighlightMode(highlightMode,selectedColor);toast(highlightMode?"하이라이트: 문장을 따라 좌우로 드래그하세요":"하이라이트를 종료했습니다");}
    private void toggleMemoMode(){placementKind="";if(renderer==null)return;memoMode=!memoMode;highlightMode=outlineMode=false;stopInk();updateToolStates();pageView.setHighlightMode(false,selectedColor);pageView.setOutlineMode(false);pageView.setMemoMode(memoMode);toast(memoMode?"메모를 놓을 위치를 탭하세요":"메모를 종료했습니다");}
    private void toggleOutlineMode(){if(renderer==null)return;outlineMode=!outlineMode;highlightMode=memoMode=false;stopInk();pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(outlineMode);updateToolStates();toast(outlineMode?"개요로 저장할 정확한 위치를 탭하세요":"개요 지점 선택을 종료했습니다");}
    private void paintTool(ImageButton button,boolean on,int background,int foreground){if(button==null)return;button.setColorFilter(on?foreground:baseTint.containsKey(button)?baseTint.get(button):NAVY);button.setBackground(on?round(background,22):round(Color.TRANSPARENT,22));}
    private void updateToolStates(){commitInlineText();syncOtherTools();updateInkButton();boolean typing=typingActive(),memo=memoMode&&!typing;paintTool(insertButton,memo||highlightMode,ACTIVE_BG,ACTIVE_FG);paintTool(readButton,!writeMode,ACTIVE_BG,ACTIVE_FG);paintTool(inkButton,writeMode,ACTIVE_BG,ACTIVE_FG);paintTool(textButton,typing,ACTIVE_BG,ACTIVE_FG);paintTool(lassoButton,pageView!=null&&pageView.isLassoMode(),ACTIVE_BG,ACTIVE_FG);}
    private void stopInk(){pageView.setLassoMode(false);inkMode=0;inkHl=false;applyInk();updateInkButton();}
    private void setInkMode(int mode){placementKind="";if(renderer==null)return;pageView.setLassoMode(false);inkMode=mode;pageView.setDirectTextSelection(false);highlightMode=memoMode=outlineMode=false;pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(false);if(mode!=1&&mode!=3)inkHl=false;applyInk();updateToolStates();updateInkButton();toast(mode==3?(inkHl?"직선 형광펜: 시작점에서 끝점까지 드래그하세요":"직선: 시작점에서 끝점까지 드래그하세요"):mode==1&&inkHl?"형광펜: 쓰듯이 자유롭게 칠하세요":mode==1?(fingerInk?"손가락 또는 S펜으로 필기하세요":"S펜으로 필기하세요. 손가락 필기는 필기도구에서 켤 수 있습니다"):mode==2?"지울 획을 터치하세요":"읽기 모드 · 빠르게 스와이프하면 페이지를 넘깁니다");}
    private int soft(int color){return (color&0xFFFFFF)|0x26000000;}
    private void updateInkButton(){boolean hl=inkHl&&(inkMode==1||inkMode==3),eraser=inkMode==2,pen=!hl&&(inkMode==1||inkMode==3);int penColor=inkColor|0xFF000000,hlColor=hlInkColor|0xFF000000;
        if(penButton!=null)baseTint.put(penButton,penColor);if(hlButton!=null)baseTint.put(hlButton,hlColor);
        paintTool(penButton,pen,soft(penColor),penColor);paintTool(hlButton,hl,soft(hlColor),hlColor);paintTool(eraserButton,eraser,0xFFFFE3E8,0xFFFF3B30);paintTool(eraserBarButton,eraser&&writeMode,0xFFFFE3E8,0xFFFF3B30);updateInkOptions();
        if(penButton!=null)penButton.setContentDescription("펜");if(inkButton!=null){inkButton.setContentDescription("필기 모드");}}
    private void toggleFingerInk(){fingerInk=!fingerInk;recentPrefs.edit().putBoolean("finger_ink",fingerInk).apply();pageView.setFingerInk(fingerInk);syncOtherTools();toast(fingerInk?"펜·지우개는 손가락으로도 사용합니다. 두 손가락으로 확대하세요":"손가락은 선택·이동, S펜은 필기에 사용합니다");}
    private void undoInk(){if(store==null)return;
        if(activeSession!=null&&activeSession.clearedPage==currentPage){store.strokes.addAll(activeSession.clearedStrokes);store.marks.addAll(activeSession.clearedMarks);activeSession.clearedStrokes.clear();activeSession.clearedMarks.clear();activeSession.clearedPage=-1;store.save();pageView.invalidate();refreshStudyPanel();toast("지운 필기를 되살렸습니다");return;}
        for(int i=store.strokes.size()-1;i>=0;i--){AnnotationStore.InkStroke s=store.strokes.get(i);if(s.page==currentPage){store.strokes.remove(i);activeSession.redoStrokes.push(s);store.save();pageView.invalidate();toast("마지막 필기를 취소했습니다");return;}}toast("취소할 필기가 없습니다");}
    private void redoInk(){if(activeSession==null||activeSession.redoStrokes.isEmpty()){toast("다시 실행할 필기가 없습니다");return;}AnnotationStore.InkStroke s=activeSession.redoStrokes.pop();store.strokes.add(s);store.save();if(s.page!=currentPage)showPage(s.page);else pageView.invalidate();}
    private void showInkHelp(){new AlertDialog.Builder(this).setTitle("필기 안내").setMessage("• S펜: 필기 또는 지우개\n• 손가락 필기 켜기: 펜·지우개 사용\n• 손가락 필기 끄기: 글자 선택·화면 이동\n• 두 손가락: 확대·이동\n• 필압: 누르는 힘에 따라 선 굵기 변화\n• S펜 측면 버튼: 누르는 동안 임시 지우개\n• 펜 뒤쪽 지우개: 지원 기기에서 자동 인식\n\n일반 정전식 펜과 손가락은 필압을 지원하지 않습니다.").setPositiveButton("확인",null).show();}
    private void toggleBookmark(){if(renderer==null)return;if(!store.bookmarks.add(currentPage))store.bookmarks.remove(currentPage);store.save();updateBookmarkButton();if(sidebarVisible&&!showAllThumbnails&&!thumbInk)rebuildThumbnails();}
    private void updateBookmarkButton(){boolean marked=renderer!=null&&store.bookmarks.contains(currentPage);bookmarkButton.setImageResource(marked?R.drawable.ic_star:R.drawable.ic_star_outline);bookmarkButton.setColorFilter(marked?0xFFF59E0B:NAVY);bookmarkButton.setContentDescription(marked?"즐겨찾기 해제":"즐겨찾기 추가");}
    /** In full screen the system navigation/status bars must stay hidden, also after a swipe from the screen edge briefly reveals them. */
    private final Runnable barHider=()->{if(!fullscreen)return;if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null)c.hide(WindowInsets.Type.systemBars());}else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);};
    /** The bottom menu and the full-screen floating dock never show together: the dock exists only in full screen, the bar only outside it. */
    private void enforceChrome(){
        if(bottomBar!=null)bottomBar.setVisibility(fullscreen?View.GONE:View.VISIBLE);
        if(!fullscreen&&fullscreenDock!=null){dockShown=false;fullscreenDock.removeCallbacks(dockHider);fullscreenDock.animate().cancel();fullscreenDock.setVisibility(View.GONE);if(dockHandle!=null)dockHandle.setVisibility(View.GONE);}
    }
    private void showFullscreenDock(boolean brief){
        if(!fullscreen){enforceChrome();return;}
        dockShown=true;if(fullscreen)root.postDelayed(barHider,250);dockHandle.setVisibility(View.GONE);fullscreenDock.removeCallbacks(dockHider);fullscreenDock.setVisibility(View.VISIBLE);dockGrip.setVisibility(dockPinned()?View.VISIBLE:View.GONE);
        if(dockPinned()){fullscreenDock.animate().cancel();fullscreenDock.setAlpha(1f);fullscreenDock.setTranslationY(0);applyFloatPos(fullscreenDock,"dock");}
        else{fullscreenDock.setTranslationX(0);fullscreenDock.setAlpha(0f);fullscreenDock.setTranslationY(dp(60));fullscreenDock.animate().alpha(1f).translationY(0).setDuration(180).start();fullscreenDock.postDelayed(dockHider,brief?3500:6000);}
    }
    private void hideFullscreenDock(boolean animate){
        if(fullscreen&&animate&&dockPinned())return;
        dockShown=false;fullscreenDock.removeCallbacks(dockHider);dockHandle.setVisibility(View.GONE);
        if(!fullscreen||!animate){fullscreenDock.animate().cancel();fullscreenDock.setVisibility(View.GONE);return;}
        fullscreenDock.animate().alpha(0f).translationY(dp(60)).setDuration(160).withEndAction(()->{if(!dockShown){fullscreenDock.setVisibility(View.GONE);if(fullscreen)dockHandle.setVisibility(View.VISIBLE);}}).start();
    }
    private void toggleFullscreen(){fullscreen=!fullscreen;railMargin();header.setVisibility(fullscreen?View.GONE:View.VISIBLE);tabStrip.setVisibility(fullscreen?View.GONE:View.VISIBLE);bottomBar.setVisibility(fullscreen?View.GONE:View.VISIBLE);if(fullscreen){showFullscreenDock(true);int hints=recentPrefs.getInt("fullscreen_hint_count",0);if(hints<3){recentPrefs.edit().putInt("fullscreen_hint_count",hints+1).apply();toast("메뉴는 화면 아래에서 위로 쓸어올리거나 아래쪽 ⌃ 버튼을 누르면 다시 나옵니다");}}else hideFullscreenDock(false);if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(fullscreen)c.hide(WindowInsets.Type.systemBars());else c.show(WindowInsets.Type.systemBars());}}else getWindow().getDecorView().setSystemUiVisibility(fullscreen?View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION:View.SYSTEM_UI_FLAG_VISIBLE);root.requestApplyInsets();}
    @Override public void onBackPressed(){if(titleEdit!=null){commitTitleEdit(activeSession,false);return;}if(inlineElement!=null){commitInlineText();return;}if(searchPanel!=null&&searchPanel.getVisibility()==View.VISIBLE){closeSearch();return;}if(fullscreen)toggleFullscreen();else super.onBackPressed();}
    @Override public void onHighlightCreated(AnnotationStore.Mark mark){store.marks.add(mark);store.save();pageView.invalidate();if(sidebarVisible&&panelTab==4)rebuildInsertions();}
    @Override public void onMemoPointRequested(int page,float x,float y){if(!placementKind.isEmpty()){createPlacedElement(page,x,y);return;}AnnotationStore.Mark m=new AnnotationStore.Mark();m.page=page;m.left=Math.max(0f,x-0.025f);m.right=Math.min(1f,x+0.025f);m.top=Math.max(0f,y-0.025f);m.bottom=Math.min(1f,y+0.025f);m.color=selectedColor;m.note="";m.noteOnly=true;
        showMemoEditor("새 메모 포스트잇",m,"저장",note->{if(note.isEmpty())return;m.note=note;store.marks.add(m);store.save();pageView.invalidate();toast("메모 포스트잇을 저장했습니다");},null,null,null,null);}
    @Override public void onMarkTapped(AnnotationStore.Mark mark){editMark(mark);}
    @Override public void onZoomGestureStarted(){stopInlinePlayer();if(highlightMode||memoMode||outlineMode){highlightMode=memoMode=outlineMode=false;pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(false);updateToolStates();}}
    @Override public void onPageSwipe(int direction){animatePage(direction);}
    private float carryScale=-1f,carryPanX,carryPanY;
    /** Remembers the zoom of the page being left so the next page opens at the same zoom and position. */
    private void carryZoom(){if(splitMode){carryScale=-1f;return;}if(pageView!=null&&Math.abs(pageView.zoom()-1f)>.001f){carryScale=pageView.zoom();carryPanX=pageView.panOffsetX();carryPanY=pageView.panOffsetY();}else carryScale=-1f;}
    private void resetPageTransforms(){for(PdfPageView v:paneViews)if(v!=null){v.animate().cancel();v.setAlpha(1f);v.setTranslationX(0);v.setTranslationY(0);v.setRotationY(0);}}
    private void animatePage(int direction){
        if(pageAnimating||renderer==null)return;int target=twoPage?(currentPage/2)*2+direction*2:currentPage+direction;if(target<0)return;if(target>=renderer.getPageCount()){if(direction>0&&isNotebook(activeSession))appendPage(activeSession,library.paper(new File(activeSession.uri.getPath())));return;}
        pageAnimating=true;carryZoom();
        if(pageAnimStyle()==2){showPage(target);resetPageTransforms();pageAnimating=false;return;}
        if(pageAnimStyle()==0&&!verticalPageSwipe&&curlPage(direction,target))return;
        if(slidePage(direction,target))return;
        showPage(target);resetPageTransforms();pageAnimating=false;
    }
    /** Slide effect: the old page is pushed out and the new one pushed in, inside the area of the page (the whole reading area, or only the touched split screen). */
    private boolean slidePage(int direction,int target){
        final View papers=viewportLayer==null?null:viewportLayer.getChildAt(0);
        if(papers==null||papers.getWidth()<=0||papers.getHeight()<=0)return false;
        final PdfPageView pane=splitMode?pageView:null;
        final int rl=pane==null?0:pane.getLeft(),rt=pane==null?0:pane.getTop(),w=pane==null?papers.getWidth():pane.getWidth(),h=pane==null?papers.getHeight():pane.getHeight();
        if(w<8||h<8)return false;
        final Bitmap oldPage,newPage;
        try{Bitmap oldFull=snapshot(papers);showPage(target);resetPageTransforms();Bitmap newFull=snapshot(papers);oldPage=slice(oldFull,rl,rt,w,h);newPage=slice(newFull,rl,rt,w,h);oldFull.recycle();newFull.recycle();}
        catch(OutOfMemoryError error){showPage(target);pageAnimating=false;return true;}
        final boolean vertical=verticalPageSwipe;final float span=vertical?h:w;
        final FrameLayout clip=new FrameLayout(this);clip.setTag("page_slide");clip.setClipChildren(true);
        final ImageView oldView=new ImageView(this),newView=new ImageView(this);oldView.setImageBitmap(oldPage);newView.setImageBitmap(newPage);
        clip.addView(oldView,new FrameLayout.LayoutParams(w,h));clip.addView(newView,new FrameLayout.LayoutParams(w,h));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(w,h,Gravity.TOP|Gravity.START);cp.leftMargin=papers.getLeft()+rl;cp.topMargin=papers.getTop()+rt;
        viewportLayer.addView(clip,1,cp);
        final float from=direction*span;
        if(vertical)newView.setTranslationY(from);else newView.setTranslationX(from);
        android.animation.ValueAnimator animator=android.animation.ValueAnimator.ofFloat(0f,1f);animator.setDuration(260);animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a->{float t=(Float)a.getAnimatedValue();float in=from*(1f-t),out=-from*t;if(vertical){newView.setTranslationY(in);oldView.setTranslationY(out);}else{newView.setTranslationX(in);oldView.setTranslationX(out);}});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){viewportLayer.removeView(clip);oldView.setImageDrawable(null);newView.setImageDrawable(null);oldPage.recycle();newPage.recycle();resetPageTransforms();pageAnimating=false;}});
        animator.start();return true;
    }
    private Bitmap snapshot(View view){Bitmap bitmap=Bitmap.createBitmap(Math.max(1,view.getWidth()),Math.max(1,view.getHeight()),Bitmap.Config.ARGB_8888);bitmap.eraseColor(darkPage()?Color.BLACK:Color.WHITE);view.draw(new Canvas(bitmap));return bitmap;}
    private static Bitmap slice(Bitmap source,int x,int y,int w,int h){Bitmap part=Bitmap.createBitmap(Math.max(1,w),Math.max(1,h),Bitmap.Config.ARGB_8888);new Canvas(part).drawBitmap(source,-x,-y,null);return part;}
    /** The part of the reading area that is really paper: the page rectangles (both pages for a spread), never the screen around them. */
    private RectF curlRegion(View papers,boolean two,PdfPageView pane){
        RectF union=null;PdfPageView[] views=two?new PdfPageView[]{firstPageView,secondPageView}:new PdfPageView[]{pane!=null?pane:firstPageView};
        for(PdfPageView v:views){RectF r=v.pageRect();if(r.isEmpty())r=new RectF(0,0,v.getWidth(),v.getHeight());if(!r.intersect(0,0,v.getWidth(),v.getHeight()))r=new RectF(0,0,v.getWidth(),v.getHeight());r.offset(v.getLeft(),v.getTop());if(union==null)union=new RectF(r);else union.union(r);}
        if(two){int spine=secondPageView.getLeft();float half=Math.min(spine-union.left,union.right-spine);if(half>0){union.left=spine-half;union.right=spine+half;}}   // symmetric about the seam, but only as wide as the paper
        return union;
    }
    private PageCurlView dragCurl;private int curlOrigin;private float dragSpan;private boolean curlConsumed;
    private final PdfPageView.PageDrag pageDragHandler=new PdfPageView.PageDrag(){
        @Override public boolean start(int direction){
            if(pageAnimating||renderer==null||verticalPageSwipe||pageAnimStyle()!=0)return false;int target=twoPage?(currentPage/2)*2+direction*2:currentPage+direction;if(target<0||target>=renderer.getPageCount())return false;
            pageAnimating=true;carryZoom();PageCurlView curl=beginCurl(direction,target);if(curl==null){pageAnimating=curlConsumed;return false;}
            dragCurl=curl;dragSpan=Math.max(dp(120),curl.contentWidth()*(twoPage?.5f:1f)*1.1f);return true;
        }
        @Override public void touchAt(float fraction){if(dragCurl!=null)dragCurl.setTouch(fraction);}
        @Override public void move(float distance){if(dragCurl!=null)dragCurl.setProgress(distance/dragSpan);}
        @Override public void end(float velocity){if(dragCurl==null)return;PageCurlView curl=dragCurl;dragCurl=null;float p=curl.progress();boolean commit=velocity>dp(700)||(velocity>-dp(700)&&p>.4f);finishCurl(curl,p,commit?1f:0f);}
    };
    /** Builds the curl overlay for the page rectangle and switches the pages underneath it; returns null when it cannot. */
    private PageCurlView beginCurl(int direction,int target){
        curlConsumed=false;final View papers=viewportLayer==null?null:viewportLayer.getChildAt(0);
        if(papers==null||papers.getWidth()<=0||papers.getHeight()<=0||firstPageView.getWidth()<=0)return null;
        final PdfPageView pane=splitMode?pageView:null;final boolean forward=direction>0,two=!splitMode&&twoPage&&secondPageView.getWidth()>0;
        RectF region=curlRegion(papers,two,pane);int rl=Math.round(region.left),rt=Math.round(region.top),w=Math.round(region.width()),h=Math.round(region.height());
        if(w<8||h<8)return null;
        final Bitmap oldFull,newFull;curlOrigin=currentPage;
        try{oldFull=snapshot(papers);showPage(target);resetPageTransforms();newFull=snapshot(papers);}
        catch(OutOfMemoryError error){showPage(target);curlConsumed=true;return null;}
        final PageCurlView curl=new PageCurlView(this);
        if(!two){
            Bitmap oldPage=slice(oldFull,rl,rt,w,h),newPage=slice(newFull,rl,rt,w,h);
            if(forward)curl.setup(null,newPage,oldPage,PageCurlView.paperBack(PageCurlView.mirror(oldPage)),false,0f);
            else curl.setup(null,PageCurlView.mirror(newPage),PageCurlView.mirror(oldPage),PageCurlView.paperBack(oldPage),true,0f);
        }else{
            int spine=secondPageView.getLeft(),half=w/2;rl=spine-half;w=half*2;
            Bitmap oldFirst=slice(oldFull,spine-half,rt,half,h),oldSecond=slice(oldFull,spine,rt,half,h),newFirst=slice(newFull,spine-half,rt,half,h),newSecond=slice(newFull,spine,rt,half,h);
            if(forward)curl.setup(oldFirst,newSecond,oldSecond,PageCurlView.paperBack(PageCurlView.mirror(newFirst)),false,.5f);
            else curl.setup(PageCurlView.mirror(oldSecond),PageCurlView.mirror(newFirst),PageCurlView.mirror(oldFirst),PageCurlView.paperBack(newSecond),true,.5f);
        }
        oldFull.recycle();newFull.recycle();
        // the overlay spans the whole reading area so the lifted corner can swing out past the page edges, not be cut at them
        curl.setOrigin(rl+papers.getLeft(),rt+papers.getTop(),w,h);viewportLayer.addView(curl,1,new FrameLayout.LayoutParams(-1,-1));return curl;
    }
    private void finishCurl(PageCurlView curl,float from,float to){
        android.animation.ValueAnimator animator=android.animation.ValueAnimator.ofFloat(from,to);animator.setDuration(Math.max(200,Math.round(1000*Math.abs(to-from))));animator.setInterpolator(from==0f?new android.view.animation.AccelerateDecelerateInterpolator():new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a->curl.setProgress((Float)a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(to<.5f){carryZoom();showPage(curlOrigin);}viewportLayer.removeView(curl);curl.release();resetPageTransforms();pageAnimating=false;}});
        animator.start();
    }
    /** Turns the page like paper: a curling leaf with a visible back side and shadows (single page and two-page spread). Returns false when it cannot animate. */
    private boolean curlPage(int direction,int target){
        PageCurlView curl=beginCurl(direction,target);
        if(curl==null){if(curlConsumed){pageAnimating=false;return true;}return false;}
        finishCurl(curl,0f,1f);return true;
    }
    /** Page-turn effect: 0 = paper curl (default), 1 = slide and fade, 2 = none. */
    private int pageAnimStyle(){return recentPrefs.getInt("page_anim_style",0);}
    private static final String[] ANIM_CHOICES={"책장 넘김 (종이처럼 접히며 넘어감)","슬라이드 (밀리며 나타남)","효과 없음 (바로 전환)"};
    private static final String[] SWIPE_CHOICES={"화살표만 · 드래그 넘김 끄기","수평 · 좌우로 넘기기","수직 · 위아래로 넘기기"};
    private List<AnchoredMenu.Row> choiceRows(String[] labels,int checked,java.util.function.IntConsumer pick){List<AnchoredMenu.Row> rows=new ArrayList<>();for(int i=0;i<labels.length;i++){final int k=i;rows.add(new AnchoredMenu.Row(labels[i],0,()->pick.accept(k)).selected(i==checked));}return rows;}
    private void setPageAnim(int which){recentPrefs.edit().putInt("page_anim_style",which).apply();toast("넘김 효과: "+ANIM_CHOICES[which].split(" \\(")[0]);}
    private void setSwipeMode(int which){swipeEnabled=which!=0;verticalPageSwipe=which==2;pageView.setVerticalPageSwipe(verticalPageSwipe);pageView.setPageSwipeEnabled(swipeEnabled);recentPrefs.edit().putBoolean("vertical_page_swipe",verticalPageSwipe).putBoolean("page_swipe_enabled_v2",swipeEnabled).apply();syncOtherTools();toast(swipeEnabled?"스와이프로도 페이지를 넘깁니다":"본문의 반투명 화살표로 페이지를 넘기세요");}
    private int swipeMode(){return !swipeEnabled?0:verticalPageSwipe?2:1;}
    private void choosePageAnimation(){showActionSheet("넘김 효과",ANIM_CHOICES,pageAnimStyle(),this::setPageAnim);}
    private boolean darkPage(){return recentPrefs.getBoolean("dark_page",false);}
    private void applyDarkPage(){boolean on=darkPage();PageCurlView.backTint=0x00FFFFFF;for(PdfPageView v:paneViews)if(v!=null)v.setDarkPage(on);}
    private void toggleDarkPage(){recentPrefs.edit().putBoolean("dark_page",!darkPage()).apply();applyDarkPage();toast(darkPage()?"문서 배경을 검게 표시합니다. 어두운 글씨 필기는 밝게 보입니다":"문서를 원래 색으로 표시합니다");}
    private boolean cropMargins(){return recentPrefs.getBoolean("crop_margins",false);}
    /** Trims blank page margins so the printed area fills the screen (not for notebooks, where the margins are writing space). */
    private void applyCrop(){
        RectF box=null;
        if(renderer!=null&&cropMargins()&&!isNotebook(activeSession)){box=firstPageView.contentBounds();if(twoPage&&secondPageView.getVisibility()==View.VISIBLE)box.union(secondPageView.contentBounds());}
        firstPageView.setCrop(box);secondPageView.setCrop(box);
    }
    @Override public void onOutlinePointRequested(int page,float x,float y){promptOutline(page,x,y,"");}
    private void promptOutline(int page,float x,float y,String suggested){EditText input=new EditText(this);input.setHint("예: 2. 세부 검토사항");if(suggested!=null&&!suggested.isEmpty())input.setText(suggested.length()>60?suggested.substring(0,60)+"…":suggested);input.setPadding(dp(24),dp(12),dp(24),dp(12));new AlertDialog.Builder(this).setTitle("개요 제목").setView(input).setPositiveButton("저장",(d,w)->{String title=input.getText().toString().trim();if(title.isEmpty())title="페이지 "+(page+1);AnnotationStore.OutlineItem item=new AnnotationStore.OutlineItem();item.page=page;item.x=x;item.y=y;item.title=title;store.outlines.add(item);store.save();if(sidebarVisible&&panelTab==2)rebuildOutlinePanel();outlineMode=false;pageView.setOutlineMode(false);updateToolStates();toast("개요에 저장했습니다");}).setNegativeButton("취소",null).show();}
    @Override public void onInkChanged(){if(store!=null){store.save();if(activeSession!=null){activeSession.redoStrokes.clear();activeSession.clearedPage=-1;}}}
    @Override public void onTextSelectionFinished(PdfPageView.TextSelection selection,float anchorX,float anchorY){showTextSelectionPopup(selection,anchorX,anchorY);}
    @Override public void onTranslationTapped(AnnotationStore.TranslationNote note){editTranslation(note);}
    private void startTextSelection(){if(renderer==null)return;setInkMode(0);pageView.setDirectTextSelection(true);syncOtherTools();recognizePageText(true);toast("텍스트 선택: 단어에서 드래그하세요");}
    private void recognizePageText(boolean announce){++ocrGeneration;recognizeViewText(pageView,announce);if(twoPage)recognizeViewText(pageView==firstPageView?secondPageView:firstPageView,false);}
    private void recognizeViewText(PdfPageView view,boolean announce){if(renderer==null||view.getVisibility()!=View.VISIBLE)return;final DocumentSession session=activeSession;final int page=view.getPageNumber();final int generation=ocrGeneration;Bitmap copy=view.copyPageBitmap();if(copy==null)return;if(announce)toast("글자를 다시 인식하는 중입니다…");if(latinRecognizer==null){latinRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);koreanRecognizer=TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());}final int width=copy.getWidth(),height=copy.getHeight();InputImage image=InputImage.fromBitmap(copy,0);latinRecognizer.process(image).addOnCompleteListener(latin->{koreanRecognizer.process(image).addOnCompleteListener(korean->{copy.recycle();if(generation!=ocrGeneration||session!=activeSession||page!=view.getPageNumber())return;Text result=null;if(latin.isSuccessful())result=latin.getResult();if(korean.isSuccessful()&&(result==null||korean.getResult().getText().length()>result.getText().length()))result=korean.getResult();if(result==null){if(announce)toast("글자를 인식하지 못했습니다");return;}List<PdfPageView.TextRegion> regions=makeTextRegions(result,width,height);session.textRegions.put(page,regions);view.setTextRegions(regions,announce);});});}
    private List<PdfPageView.TextRegion> makeTextRegions(Text text,int width,int height){List<PdfPageView.TextRegion> out=new ArrayList<>();for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){android.graphics.Rect lb=line.getBoundingBox();if(lb==null)continue;RectF lineBox=normalized(lb,width,height);for(Text.Element element:line.getElements()){android.graphics.Rect eb=element.getBoundingBox();if(eb!=null&&!element.getText().trim().isEmpty())out.add(new PdfPageView.TextRegion(element.getText().trim(),line.getText().trim(),normalized(eb,width,height),lineBox));}}return out;}
    private RectF normalized(android.graphics.Rect r,int w,int h){return new RectF(Math.max(0f,(float)r.left/w),Math.max(0f,(float)r.top/h),Math.min(1f,(float)r.right/w),Math.min(1f,(float)r.bottom/h));}
    private PopupWindow selectionPopup;
    @Override public void onSelectionAdjustStarted(){if(selectionPopup!=null){selectionPopup.dismiss();selectionPopup=null;}}
    /** One menu for everything: the selection actions on top, then the same insert rows as the long-press menu, in the same card style. */
    private void showTextSelectionPopup(PdfPageView.TextSelection selection,float anchorX,float anchorY){
        onSelectionAdjustStarted();
        final PdfPageView view=pageView;final int page=currentPage;
        Runnable[] after=new Runnable[1];after[0]=()->{onSelectionAdjustStarted();view.clearTextSelectionOverlay();};
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        String[] labels={"하이라이트","복사","번역","읽어주기","단어장","개요","메모","발췌","링크"};
        int[] icons={R.drawable.ic_highlight,R.drawable.ic_copy,R.drawable.ic_translate,R.drawable.ic_speaker,R.drawable.ic_dictionary,R.drawable.ic_outline,R.drawable.ic_memo,R.drawable.ic_basket,R.drawable.ic_link};
        int[] tints={0xFFF5A623,0xFF8E8E93,0xFF007AFF,0xFF34C759,0xFF30B0C7,0xFF5856D6,0xFFFF9500,0xFFAF52DE,0xFF5856D6};
        Runnable[] actions={()->addOcrHighlights(selection.bounds),()->copySelectedText(selection.text),()->translateText(selection.text,selection.unionBounds),
            ()->readAloud(selection.text),()->openDictionary(selection.text),()->promptOutline(page,selection.unionBounds.left,selection.unionBounds.top,selection.text),
            ()->onMemoPointRequested(page,selection.unionBounds.right,selection.unionBounds.top),
            ()->addStudyEntry(selection.text,selection.unionBounds.left,selection.unionBounds.top,true),()->createHyperlink(selection)};
        for(int i=0;i<labels.length;i++){final int index=i;rows.add(new AnchoredMenu.Row(labels[i],icons[i],()->{actions[index].run();after[0].run();}).tint(tints[i]));}
        rows.add(AnchoredMenu.Row.divider());
        dropTarget=new float[]{page,selection.unionBounds.left,selection.unionBounds.bottom};dropTime=System.currentTimeMillis();
        RectF pageBox=view.pageRect();int[] where=new int[2];view.getLocationOnScreen(where);RectF u=selection.unionBounds;
        android.graphics.Rect avoid=new android.graphics.Rect(Math.round(where[0]+pageBox.left+u.left*pageBox.width()),Math.round(where[1]+pageBox.top+u.top*pageBox.height()),Math.round(where[0]+pageBox.left+u.right*pageBox.width()),Math.round(where[1]+pageBox.top+u.bottom*pageBox.height()));
        rows.add(new AnchoredMenu.Row("삽입",R.drawable.ic_insert,()->{dropTarget=new float[]{page,selection.unionBounds.left,selection.unionBounds.bottom};dropTime=System.currentTimeMillis();showMenuAt(view,anchorX,anchorY,insertRows(),view::clearTextSelectionOverlay,avoid);}).submenu().tint(0xFFFF2D55));
        showMenuAt(view,anchorX,anchorY,rows,view::clearTextSelectionOverlay,avoid);
    }
    private void copySelectedText(String text){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("PDF 선택 문장",text));toast("선택한 내용을 복사했습니다");}
    /** Opens the 영어 스터디 app (com.hdlee73.englishstudy). Its dictionary tab searches a copied English word when it comes to the front, so the word is placed on the clipboard first. */
    private void openDictionary(String word){
        String query=word==null?"":word.replaceAll("^[^A-Za-z]+|[^A-Za-z'-]+$","").trim();
        Intent launch=getPackageManager().getLaunchIntentForPackage("com.hdlee73.englishstudy");
        if(launch==null){new AlertDialog.Builder(this).setTitle("영어 스터디 앱이 필요합니다").setMessage("영어 스터디 앱을 설치하면 선택한 단어를 바로 검색할 수 있습니다.\nhttps://github.com/hdlee73/english_study/releases").setPositiveButton("확인",null).show();return;}
        if(!query.isEmpty()){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("PDF 선택 단어",query));}
        // The word also travels as an extra: the 영어 스터디 app switches to its dictionary tab, searches it and shows an "Everynote로 돌아가기" bar.
        if(!query.isEmpty())launch.putExtra("com.hdlee73.englishstudy.extra.LOOKUP_WORD",query);
        launch.putExtra("com.hdlee73.englishstudy.extra.RETURN_PACKAGE",getPackageName());
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(launch);}catch(RuntimeException error){toast("영어 스터디 앱을 열 수 없습니다");}
    }
    private void addOcrHighlights(List<RectF> bounds){for(RectF b:bounds){AnnotationStore.Mark m=new AnnotationStore.Mark();m.page=currentPage;m.left=b.left;m.top=b.top;m.right=b.right;m.bottom=b.bottom;m.color=selectedColor;store.marks.add(m);}store.save();pageView.invalidate();toast("선택한 범위를 하이라이트했습니다");}
    private void translateOffline(String source,RectF bounds){final DocumentSession target=activeSession;final int page=currentPage;boolean korean=source.matches(".*[가-힣].*");TranslatorOptions options=new TranslatorOptions.Builder().setSourceLanguage(korean?TranslateLanguage.KOREAN:TranslateLanguage.ENGLISH).setTargetLanguage(korean?TranslateLanguage.ENGLISH:TranslateLanguage.KOREAN).build();Translator translator=Translation.getClient(options);ProgressDialog progress=ProgressDialog.show(this,"번역","번역 모델을 준비하는 중입니다…",true,false);translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()).onSuccessTask(v->translator.translate(source)).addOnSuccessListener(result->{progress.dismiss();translator.close();if(isFinishing()||isDestroyed()||!sessions.contains(target))return;switchDocument(target);showPage(page);showTranslationResult(source,result,bounds);}).addOnFailureListener(e->{progress.dismiss();translator.close();toast("번역 실패: 인터넷 연결을 확인하세요");});}
    /** The translation is placed on the page as a post-it immediately; this card shows original and result and lets the user fix, copy or delete it. */
    private void showTranslationResult(String source,String translated,RectF bounds){
        final AnnotationStore targetStore=store;final int targetPage=currentPage;
        final AnnotationStore.TranslationNote note=new AnnotationStore.TranslationNote();note.page=targetPage;note.left=bounds.left;note.top=bounds.top;note.right=bounds.right;note.bottom=bounds.bottom;note.source=source;note.translated=translated.trim();
        final boolean toKorean=!source.matches(".*[가-힣].*");
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setTag("translation_card");card.setBackground(round(Color.WHITE,24));card.setPadding(dp(20),dp(18),dp(20),dp(8));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout chip=new FrameLayout(this);chip.setBackground(round(0x1F007AFF,12));ImageView glyph=new ImageView(this);glyph.setImageResource(R.drawable.ic_translate);glyph.setColorFilter(ACCENT);chip.addView(glyph,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));head.addView(chip,new LinearLayout.LayoutParams(dp(38),dp(38)));
        TextView title=new TextView(this);title.setText("번역");title.setTextSize(19);title.setTextColor(NAVY);title.setTypeface(Typeface.DEFAULT_BOLD);title.setPadding(dp(10),0,0,0);head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView pair=new TextView(this);pair.setText(toKorean?"영어 → 한국어":"한국어 → 영어");pair.setTextSize(12);pair.setTextColor(ACCENT);pair.setBackground(round(0x1A007AFF,12));pair.setPadding(dp(10),dp(4),dp(10),dp(4));head.addView(pair,new LinearLayout.LayoutParams(-2,-2));
        card.addView(head,new LinearLayout.LayoutParams(-1,-2));
        TextView originalLabel=new TextView(this);originalLabel.setText("원문");originalLabel.setTextSize(12);originalLabel.setTextColor(0xFF8E8E93);originalLabel.setPadding(dp(2),dp(16),0,dp(4));card.addView(originalLabel);
        TextView original=new TextView(this);original.setText(source);original.setTextSize(14);original.setTextColor(0xFF48484A);original.setLineSpacing(0,1.25f);original.setTextIsSelectable(true);original.setBackground(round(0xFFF2F2F7,14));original.setPadding(dp(14),dp(10),dp(14),dp(10));original.setMaxLines(5);
        ScrollView originalScroll=new ScrollView(this);originalScroll.addView(original);card.addView(originalScroll,new LinearLayout.LayoutParams(-1,-2));
        TextView resultLabel=new TextView(this);resultLabel.setText("번역 · 직접 고칠 수 있습니다");resultLabel.setTextSize(12);resultLabel.setTextColor(0xFF8E8E93);resultLabel.setPadding(dp(2),dp(14),0,dp(4));card.addView(resultLabel);
        final EditText result=new EditText(this);result.setTag("translation_result");result.setText(note.translated);result.setTextSize(17);result.setTextColor(NAVY);result.setLineSpacing(0,1.25f);result.setMinLines(2);result.setMaxLines(8);result.setGravity(Gravity.TOP);result.setBackground(round(0xFFEAF3FF,14));result.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.addView(result,new LinearLayout.LayoutParams(-1,-2));
        View line=new View(this);line.setBackgroundColor(0xFFE5E5EA);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2));lp.topMargin=dp(14);card.addView(line,lp);
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(dialogButton("복사",0xFF8E8E93,false,()->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("번역",result.getText().toString()));toast("번역을 복사했습니다");}),new LinearLayout.LayoutParams(-2,dp(48)));
        buttons.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        buttons.addView(dialogButton("닫기",0xFF8E8E93,false,dialog::dismiss),new LinearLayout.LayoutParams(-2,dp(48)));
        buttons.addView(dialogButton("포스트잇 붙이기",ACCENT,true,()->{String value=result.getText().toString().trim();if(!value.isEmpty())note.translated=value;targetStore.translations.add(note);targetStore.save();pageView.invalidate();dialog.dismiss();toast("페이지에 포스트잇으로 붙였습니다");}),new LinearLayout.LayoutParams(-2,dp(48)));
        card.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.addView(card);dialog.setContentView(scroll);
        Window window=dialog.getWindow();if(window!=null){window.setGravity(Gravity.CENTER);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(460)),ViewGroup.LayoutParams.WRAP_CONTENT);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        dialog.setCanceledOnTouchOutside(true);dialog.show();
    }
    private void editTranslation(AnnotationStore.TranslationNote note){EditText input=new EditText(this);input.setText(note.translated);input.setMinLines(3);new AlertDialog.Builder(this).setTitle("번역 포스트잇 · p."+(note.page+1)).setMessage("원문: "+note.source).setView(input).setPositiveButton("저장",(d,w)->{note.translated=input.getText().toString().trim();store.save();pageView.invalidate();}).setNegativeButton("삭제",(d,w)->{store.translations.remove(note);store.save();pageView.invalidate();toast("번역 포스트잇을 삭제했습니다");}).setNeutralButton("표시 설정",(d,w)->showTranslationDisplayOptions(note)).show();}
    private void showTranslationDisplayOptions(AnnotationStore.TranslationNote note){String[] choices={"펼쳐서 표시","최소화","숨기기"};int checked=!note.visible?2:(note.minimized?1:0);showActionSheet("번역 포스트잇 표시",choices,checked,w->{note.visible=w!=2;note.minimized=w==1;store.save();pageView.invalidate();});}
    private void showTranslations(){if(store==null||store.translations.isEmpty()){toast("저장된 번역 포스트잇이 없습니다");return;}List<AnnotationStore.TranslationNote> items=new ArrayList<>(store.translations);String[] labels=new String[items.size()];for(int i=0;i<items.size();i++){AnnotationStore.TranslationNote n=items.get(i);String state=!n.visible?"숨김":(n.minimized?"최소화":"펼침");labels[i]="p."+(n.page+1)+"  ["+state+"] "+(n.translated.length()>35?n.translated.substring(0,35)+"…":n.translated);}new AlertDialog.Builder(this).setTitle("번역 포스트잇").setItems(labels,(d,i)->{showPage(items.get(i).page);editTranslation(items.get(i));}).show();}
    private void editMark(AnnotationStore.Mark mark){
        showMemoEditor("페이지 "+(mark.page+1)+(mark.noteOnly?" 메모 포스트잇":" 하이라이트"),mark,"저장",text->{mark.note=text;mark.visible=true;store.save();pageView.invalidate();},
            mark.noteOnly?"메모 삭제":"하이라이트 삭제",()->{store.marks.remove(mark);store.save();pageView.invalidate();toast(mark.noteOnly?"메모를 삭제했습니다":"하이라이트를 삭제했습니다");},"표시 설정",()->showMemoDisplayOptions(mark));
    }
    private void showMemoDisplayOptions(AnnotationStore.Mark mark){String[] choices={"펼쳐서 표시","최소화","숨기기"};int checked=!mark.visible?2:(mark.minimized?1:0);showActionSheet("메모 포스트잇 표시",choices,checked,w->{mark.visible=w!=2;mark.minimized=w==1;store.save();pageView.invalidate();});}
    private List<AnchoredMenu.Row> addDocumentRows(){return AnchoredMenu.rows(new AnchoredMenu.Row("새 노트 만들기",R.drawable.ic_compose,this::newNotebook).tint(0xFF34C759),new AnchoredMenu.Row("파일 가져오기",R.drawable.ic_import,this::choosePdf).tint(0xFF007AFF),new AnchoredMenu.Row("저장된 문서 열기",R.drawable.ic_folder_open,this::showLibrary).tint(0xFFF5A623));}
    private void showAddDocumentMenu(){AnchoredMenu.showCentered(this,getWindow().getDecorView(),"문서 추가",addDocumentRows());}
    private void showOutlineList(){
        if(store==null){toast("PDF를 먼저 여세요");return;}
        if(sidebarVisible&&panelTab==2){closeSidePanel();return;}
        selectPanelTab(2);
    }
    private void showOutlineItem(AnnotationStore.OutlineItem item,View anchor){AnchoredMenu.showRightOf(this,anchor,sidePanel,AnchoredMenu.rows(
        new AnchoredMenu.Row("이동",R.drawable.ic_page,()->{showPage(item.page);pageView.post(()->pageView.focusOnPoint(item.x,item.y));}).tint(0xFF30B0C7),
        new AnchoredMenu.Row("삭제",R.drawable.ic_delete,()->{store.outlines.remove(item);store.save();toast("개요 항목을 삭제했습니다");if(sidebarVisible&&panelTab==2)rebuildOutlinePanel();}).danger()));}
    private void choosePageSwipeDirection(){showActionSheet("페이지 넘김",SWIPE_CHOICES,swipeMode(),this::setSwipeMode);}
    private void showMarkList(){List<AnnotationStore.Mark> items=new ArrayList<>(store.marks);if(items.isEmpty()){toast("저장된 하이라이트나 메모가 없습니다");return;}String[] labels=new String[items.size()];for(int i=0;i<items.size();i++){AnnotationStore.Mark mark=items.get(i);String note=mark.note;String state=note==null||note.isEmpty()?"":(!mark.visible?"[숨김] ":(mark.minimized?"[최소화] ":"[펼침] "));labels[i]="p."+(mark.page+1)+"  "+(mark.noteOnly?"메모":"하이라이트")+"  "+state+(note==null||note.isEmpty()?"":note);}new AlertDialog.Builder(this).setTitle("메모·하이라이트").setItems(labels,(d,i)->{showPage(items.get(i).page);editMark(items.get(i));}).show();}
    private void showBookmarks(){if(store.bookmarks.isEmpty()){toast("즐겨찾기한 페이지가 없습니다");return;}List<Integer> pages=new ArrayList<>(store.bookmarks);Collections.sort(pages);String[] labels=new String[pages.size()];for(int i=0;i<pages.size();i++)labels[i]="페이지 "+(pages.get(i)+1);new AlertDialog.Builder(this).setTitle("즐겨찾기").setItems(labels,(d,i)->showPage(pages.get(i))).show();}
    private void goToPage(){if(renderer==null)return;EditText input=new EditText(this);input.setInputType(2);input.setHint("1 ~ "+renderer.getPageCount());new AlertDialog.Builder(this).setTitle("페이지로 이동").setView(input).setPositiveButton("이동",(d,w)->{try{showPage(Integer.parseInt(input.getText().toString())-1);}catch(Exception ignored){toast("올바른 페이지를 입력하세요");}}).setNegativeButton("취소",null).show();}
    private void exportAnnotations(){if(documentUri==null)return;try{pendingJsonExport=store.exportJson(documentUri,documentTitle);}catch(JSONException error){toast("백업 실패");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,documentTitle.replaceAll("(?i)\\.pdf$","")+"_annotations.json");startActivityForResult(i,EXPORT_JSON);}
    @Override public void onLassoSelectionFinished(){
        final Bitmap capture;
        try{capture=pageView.captureLasso();}catch(OutOfMemoryError|RuntimeException error){pageView.clearLassoSelection();toast("캡처할 영역을 조금 줄여 주세요");return;}
        if(capture==null)return;
        final String selectedText=pageView.lassoText();final int capturedPage=currentPage;final String capturedTitle=documentTitle;
        final boolean[] handedOff={false};
        // the same floating card as the other menus: a small preview, then one row per action
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(round(0xFFF2F2F7,12));preview.setImageBitmap(capture);preview.setTag("lasso_preview");
        FrameLayout previewBox=new FrameLayout(this);previewBox.setPadding(dp(6),dp(2),dp(6),dp(6));previewBox.addView(preview,new FrameLayout.LayoutParams(-1,dp(130)));
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        rows.add(AnchoredMenu.Row.custom(previewBox));
        rows.add(new AnchoredMenu.Row("이미지 복사",R.drawable.ic_copy,()->{handedOff[0]=true;writeCapture(capture,0,capturedTitle,capturedPage);}).tint(0xFF007AFF));
        rows.add(new AnchoredMenu.Row("PNG 저장",R.drawable.ic_folder_open,()->{handedOff[0]=true;writeCapture(capture,1,capturedTitle,capturedPage);}).tint(0xFF34C759));
        rows.add(new AnchoredMenu.Row("이미지 공유",R.drawable.ic_share,()->{handedOff[0]=true;writeCapture(capture,2,capturedTitle,capturedPage);}).tint(0xFF5856D6));
        if(!selectedText.isEmpty())rows.add(new AnchoredMenu.Row("글자 복사",R.drawable.ic_scan,()->copySelectedText(selectedText)).tint(0xFF30B0C7));
        rows.add(AnchoredMenu.Row.divider());
        rows.add(new AnchoredMenu.Row("다시 선택",R.drawable.ic_lasso,()->{}).tint(0xFFAF52DE));
        rows.add(new AnchoredMenu.Row("선택 종료",R.drawable.ic_check,()->{setInkMode(0);updateToolStates();}).tint(0xFF8E8E93));
        PopupWindow menu=AnchoredMenu.showCentered(this,pageView,"올가미 캡처 · p."+(capturedPage+1),rows);
        // a tapped row dismisses the card before its action runs, so the bitmap is recycled only after the action had its chance to take it over
        menu.setOnDismissListener(()->{preview.setImageDrawable(null);pageView.clearLassoSelection();pageView.post(()->{if(!handedOff[0]&&!capture.isRecycled())capture.recycle();});});
    }
    private void writeCapture(Bitmap image,int action,String title,int page){
        new Thread(()->{
            File file=null;
            try{
                File directory=new File(getCacheDir(),"captures");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("캡처 폴더를 만들 수 없습니다");
                File[] old=directory.listFiles();if(old!=null)for(File item:old)if(item.getName().matches("[a-f0-9-]{36}\\.png")&&System.currentTimeMillis()-item.lastModified()>7L*24*60*60*1000)item.delete();
                file=new File(directory,UUID.randomUUID()+".png");try(OutputStream out=new FileOutputStream(file)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG 저장 실패");}
                final File ready=file;
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;Uri uri=CaptureProvider.uri(this,ready);
                    if(action==0){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(),"Everynote 영역 캡처",uri));toast("이미지를 복사했습니다. 이미지 붙여넣기를 지원하는 앱에서 사용하세요");}
                    else if(action==1){pendingCaptureExport=ready;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE,title.replaceAll("(?i)\\.pdf$","")+"_p"+(page+1)+"_capture.png");startActivityForResult(intent,EXPORT_CAPTURE);}
                    else{Intent intent=new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newUri(getContentResolver(),"Everynote 캡처",uri));try{startActivity(Intent.createChooser(intent,"캡처 이미지 공유"));}catch(ActivityNotFoundException error){toast("이미지를 받을 앱이 없습니다");}}
                });
            }catch(Exception error){if(file!=null)file.delete();runOnUiThread(()->toast("캡처 실패: "+error.getMessage()));}
            finally{image.recycle();}
        },"lasso-capture").start();
    }
    private void receiveCaptureExport(int result,Intent data){
        final File source=pendingCaptureExport;pendingCaptureExport=null;
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(source==null){toast("올가미로 다시 캡처해 주세요");return;}
        final Uri destination=data.getData();
        new Thread(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
            if(out==null)throw new IOException("저장할 파일을 열 수 없습니다");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            runOnUiThread(()->toast("PNG 캡처를 저장했습니다"));
        }catch(Exception error){runOnUiThread(()->toast("캡처 저장 실패: "+error.getMessage()));}},"lasso-export").start();
    }
    private void buildStudyPanel(){
        studyPanel=new LinearLayout(this);studyPanel.setOrientation(LinearLayout.VERTICAL);studyPanel.setPadding(dp(8),dp(4),dp(8),dp(4));studyPanel.setBackgroundColor(0xFFFFFBF1);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        studyHeading=new TextView(this);studyHeading.setTextSize(14);studyHeading.setTextColor(NAVY);bar.addView(studyHeading,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(icon(R.drawable.ic_note_add,"현재 페이지에 노트 추가",NAVY,v->addStudyEntry("",0.5f,0.5f,false)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        bar.addView(icon(R.drawable.ic_more_vert,"노트 메뉴",NAVY,v->AnchoredMenu.show(this,v,false,AnchoredMenu.rows(
            new AnchoredMenu.Row("전체 노트",R.drawable.ic_notes_all,()->{basketOnly=false;refreshStudyPanel();}).tint(0xFF007AFF).selected(!basketOnly),
            new AnchoredMenu.Row("발췌만 보기",R.drawable.ic_basket,()->{basketOnly=true;refreshStudyPanel();}).tint(0xFFF5A623).selected(basketOnly),
            new AnchoredMenu.Row("내보내기",R.drawable.ic_export,this::exportStudy).tint(0xFF34C759),
            AnchoredMenu.Row.divider(),
            new AnchoredMenu.Row("닫기",R.drawable.ic_close,()->{studyVisible=false;layoutStudyPanel();}).tint(0xFF8E8E93)),null)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        studyPanel.addView(bar);ScrollView scroll=new ScrollView(this);studyRows=new LinearLayout(this);studyRows.setOrientation(LinearLayout.VERTICAL);scroll.addView(studyRows);studyPanel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void layoutStudyPanel(){
        if(studySplit==null)return;boolean wide=getResources().getConfiguration().screenWidthDp>=600;
        studySplit.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);studyPanel.setVisibility(studyVisible?View.VISIBLE:View.GONE);
        pdfArea.setLayoutParams(new LinearLayout.LayoutParams(wide?0:-1,wide?-1:0,studyVisible?1.3f:1f));
        studyPanel.setLayoutParams(new LinearLayout.LayoutParams(wide?0:-1,wide?-1:0,1f));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);layoutStudyPanel();}
    private void showStudy(boolean basket){if(store==null){toast("PDF를 먼저 여세요");return;}studyVisible=true;basketOnly=basket;layoutStudyPanel();refreshStudyPanel();}
    private void addStudyEntry(String text,float x,float y,boolean excerpt){
        if(store==null)return;AnnotationStore.StudyEntry entry=new AnnotationStore.StudyEntry();entry.page=currentPage;entry.x=x;entry.y=y;entry.text=text;entry.excerpt=excerpt;
        if(excerpt){store.studyEntries.add(entry);store.save();showStudy(true);toast("발췌 바구니에 저장했습니다");}else editStudyEntry(entry,true);
    }
    private void editStudyEntry(AnnotationStore.StudyEntry entry,boolean fresh){
        final AnnotationStore target=store;LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),0,dp(18),0);
        EditText text=new EditText(this);text.setHint("노트 / 발췌 원문 · Markdown 입력");text.setText(entry.text);text.setMinLines(3);text.setMaxLines(6);panel.addView(text);
        EditText comment=new EditText(this);comment.setHint("설명");comment.setText(entry.comment);comment.setMaxLines(4);panel.addView(comment);
        new AlertDialog.Builder(this).setTitle("[p."+(entry.page+1)+"] "+(entry.excerpt?"발췌":"노트")).setView(panel)
            .setPositiveButton("저장",(d,w)->{if(text.getText().toString().trim().isEmpty()){toast("노트 내용을 입력하세요");return;}entry.text=text.getText().toString();entry.comment=comment.getText().toString();if(fresh)target.studyEntries.add(entry);target.save();showStudy(false);})
            .setNegativeButton("취소",null).setNeutralButton(fresh?"닫기":"삭제",(d,w)->{if(!fresh){target.studyEntries.remove(entry);target.save();refreshStudyPanel();}}).show();
    }
    private void refreshStudyPanel(){
        if(studyRows==null)return;studyRows.removeAllViews();studyHeading.setText((basketOnly?"발췌 바구니":"노트")+" · p."+(currentPage+1));if(store==null)return;
        int count=0;for(AnnotationStore.StudyEntry entry:store.studyEntries){if(basketOnly&&!entry.excerpt)continue;count++;
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(10),dp(6),dp(10),dp(8));card.setBackground(round(entry.page==currentPage?0xFFFFEDB6:Color.WHITE,12));
            TextView link=new TextView(this);link.setText("[p."+(entry.page+1)+"] ↗");link.setTextColor(ACCENT);link.setTextSize(14);link.setMinHeight(dp(40));link.setGravity(Gravity.CENTER_VERTICAL);
            link.setOnClickListener(v->{showPage(entry.page);pageView.post(()->pageView.focusOnPoint(entry.x,entry.y));});card.addView(link);
            TextView body=new TextView(this);body.setText(entry.text+(entry.comment.isEmpty()?"":"\n\n"+entry.comment));body.setTextSize(15);body.setTextColor(NAVY);body.setOnClickListener(v->editStudyEntry(entry,false));card.addView(body);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(8);studyRows.addView(card,params);
        }
        if(count==0){TextView empty=new TextView(this);empty.setText(basketOnly?"본문을 드래그한 뒤 ‘발췌’를 누르세요":"＋로 현재 페이지에 연결된 노트를 작성하세요.\n[p.] 링크를 누르면 원문 위치로 이동합니다.");empty.setPadding(dp(10),dp(12),dp(10),dp(12));studyRows.addView(empty);}
    }
    private void exportStudy(){
        if(store==null)return;new AlertDialog.Builder(this).setTitle("노트·발췌 내보내기").setItems(new String[]{"Markdown (.md)","CSV (.csv)","Excel (.xlsx)","PDF (.pdf)","Word (.docx)"},(d,format)->{
            List<AnnotationStore.StudyEntry> entries=new ArrayList<>();for(AnnotationStore.StudyEntry e:store.studyEntries)if(!basketOnly||e.excerpt)entries.add(e);
            if(entries.isEmpty()){toast("내보낼 항목이 없습니다");return;}try{pendingExport=StudyExporter.export(entries,documentTitle,format);}catch(IOException error){toast("내보내기 실패");return;}
            String[] extensions={"md","csv","xlsx","pdf","docx"},types={"text/markdown","text/csv","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document"};
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(types[format]).putExtra(Intent.EXTRA_TITLE,documentTitle.replaceAll("(?i)\\.pdf$","")+"_notes."+extensions[format]),EXPORT_STUDY);
        }).show();
    }
    private void importSidecar(){if(store==null)return;importTarget=store;importSession=activeSession;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),IMPORT_SIDECAR);}
    private void receiveStudyResult(int request,int result,Intent data){
        if(result!=RESULT_OK||data==null||data.getData()==null){if(request==EXPORT_STUDY)pendingExport=null;else{importTarget=null;importSession=null;}return;}
        if(request==EXPORT_STUDY){try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null||pendingExport==null)throw new IOException("다시 내보내세요");out.write(pendingExport);toast("내보냈습니다");}catch(Exception error){toast("내보내기 실패: "+error.getMessage());}finally{pendingExport=null;}return;}
        final AnnotationStore target=importTarget;final DocumentSession session=importSession;importTarget=null;importSession=null;
        if(session==null||!sessions.contains(session))return;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){
            if(in==null)throw new IOException("파일을 읽을 수 없습니다");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>16*1024*1024)throw new IOException("백업은 16MB 이하만 지원합니다");out.write(buffer,0,n);}
            String json=out.toString("UTF-8");JSONObject root=new JSONObject(json);
            new AlertDialog.Builder(this).setTitle("필기 백업 불러오기").setMessage("백업 문서: "+root.optString("document")+"\n현재 문서: "+session.title+"\n\n현재 문서의 주석·노트·발췌를 이 백업으로 교체합니다.")
                .setPositiveButton("복원",(d,w)->{if(!sessions.contains(session))return;try{target.importJson(json,session.renderer.getPageCount());session.redoStrokes.clear();switchDocument(session);toast("주석과 노트를 복원했습니다");}catch(JSONException error){toast("복원 실패: "+error.getMessage());}}).setNegativeButton("취소",null).show();
        }catch(Exception error){toast("백업 읽기 실패: "+error.getMessage());}
    }
    private SettingsDialog settingsDialog;
    /** Settings screen: default note style, reading comfort and page-turning options (the same prefs the in-document menus use). */
    private void showSettings(){
        if(settingsDialog!=null&&settingsDialog.isShowing())return;
        settingsDialog=new SettingsDialog(this,new SettingsDialog.Host(){
            public android.content.SharedPreferences prefs(){return recentPrefs;}
            public boolean keepAwake(){return recentPrefs.getBoolean("keep_awake",false);}
            public void setKeepAwake(boolean on){recentPrefs.edit().putBoolean("keep_awake",on).apply();applyKeepAwake();}
            public boolean dockPinned(){return MainActivity.this.dockPinned();}
            public void toggleDockPinned(){MainActivity.this.toggleDockPinned();}
            public boolean cropMargins(){return MainActivity.this.cropMargins();}
            public void setCropMargins(boolean on){recentPrefs.edit().putBoolean("crop_margins",on).apply();applyCrop();}
            public boolean darkPage(){return MainActivity.this.darkPage();}
            public void toggleDarkPage(){MainActivity.this.toggleDarkPage();}
            public String[] swipeChoices(){return SWIPE_CHOICES;}
            public int swipeMode(){return MainActivity.this.swipeMode();}
            public void setSwipeMode(int which){MainActivity.this.setSwipeMode(which);}
            public String[] animChoices(){return ANIM_CHOICES;}
            public int pageAnim(){return pageAnimStyle();}
            public void setPageAnim(int which){MainActivity.this.setPageAnim(which);}
            public String pendingUpdate(){return pendingUpdateVersion();}
            public void showAbout(){MainActivity.this.showAbout();}
            public void showHelp(){MainActivity.this.showHelp();}
            public void requestTemplate(PaperChoiceView view){MainActivity.this.requestTemplate(view);}
        });settingsDialog.show();
    }
    private void showLibrary(){
        onSelectionAdjustStarted();if(store!=null)store.save();if(libraryDialog!=null&&libraryDialog.isShowing())return;
        if(libraryFolder==null||!libraryFolder.isDirectory())libraryFolder=library.root;
        libraryDialog=new LibraryDialog(this,library,libraryFolder,new LibraryDialog.Actions(){
            public void open(File file){openPdf(Uri.fromFile(file));}
            public void importFiles(File folder){libraryFolder=folder;choosePdf();}
            public void newNote(File folder,Runnable refresh){createNotebook(folder,refresh);}
            public void changed(File source,File target,boolean moved){if(moved)libraryChanged(source,target);}
            public void selectedFolder(File folder){libraryFolder=folder;}
            public void settings(){showSettings();}
            public void removed(List<File> files){for(DocumentSession session:new ArrayList<>(sessions))if(files.contains(new File(session.uri.getPath())))closeDocument(session);}
        });libraryDialog.show();
    }
    /** Lets the user pick a PDF or picture to use as the background of new note pages (kept as a private copy). */
    private void requestTemplate(PaperChoiceView target){
        templateTarget=target;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/*"});
        try{startActivityForResult(pick,IMPORT_TEMPLATE);}catch(RuntimeException error){toast("파일 선택기를 열 수 없습니다");}
    }
    private void receiveTemplate(Uri source){
        final PaperChoiceView target=templateTarget;if(target==null)return;
        new Thread(()->{try{
            String mime=getContentResolver().getType(source);String label="서식";try(android.database.Cursor c=getContentResolver().query(source,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&c.getString(0)!=null)label=c.getString(0);}
            boolean pdf=(mime!=null&&mime.contains("pdf"))||label.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
            String stem=label.replaceAll("[\\\\/:*?\"<>|]","_");int dot=stem.lastIndexOf('.');if(dot>0)stem=stem.substring(0,dot);
            File folder=new File(getFilesDir(),"templates");folder.mkdirs();File out=NotebookFiles.unique(folder,stem+(pdf?".pdf":".img"));
            try(InputStream in=getContentResolver().openInputStream(source);OutputStream o=new FileOutputStream(out)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)o.write(buffer,0,n);}
            runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())target.setTemplate(out);});
        }catch(Exception error){runOnUiThread(()->toast("서식 파일을 가져오지 못했습니다"));}},"template-import").start();
    }
    private void newNotebook(){createNotebook(libraryFolder==null?library.root:libraryFolder,()->{});}
    private void createNotebook(File folder,Runnable refresh){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);EditText name=new EditText(this);name.setSingleLine();name.setHint("노트 이름");name.setText("새 노트");name.setPadding(dp(18),dp(12),dp(18),dp(12));panel.addView(name,new LinearLayout.LayoutParams(-1,dp(56)));PaperChoiceView.Style style=PaperChoiceView.defaultStyle(recentPrefs);PaperChoiceView paper=new PaperChoiceView(this,style.kind,style.color,style.landscape,true);paper.onTemplateRequest(()->requestTemplate(paper));panel.addView(paper);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("새 노트").setView(panel).setPositiveButton("만들기",null).setNegativeButton("취소",null).setWidth(400).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String title=NotebookFiles.name(name.getText().toString());NotebookFiles.Paper selected=paper.paper();dialog.getButton(-1).setEnabled(false);new Thread(()->{try{File file=library.createNote(folder,title,selected,paper.landscape());runOnUiThread(()->{if(isFinishing()||isDestroyed())return;dialog.dismiss();refresh.run();if(libraryDialog!=null)libraryDialog.dismiss();openPdf(Uri.fromFile(file));toast("마지막 장에서 넘기면 새 페이지가 추가됩니다");});}catch(Exception error){runOnUiThread(()->{dialog.getButton(-1).setEnabled(true);name.setError(error.getMessage());});}},"new-notebook").start();}catch(Exception error){name.setError(error.getMessage());}}));dialog.show();
    }
    private boolean isNotebook(DocumentSession session){return session!=null&&library.managed(session.uri)&&library.paper(new File(session.uri.getPath()))!=null;}
    private void chooseAddedPage(){choosePageToInsert(renderer==null?0:renderer.getPageCount()-1);}
    private void appendPage(DocumentSession session,NotebookFiles.Paper paper){insertPage(session,paper,session.renderer.getPageCount()-1);}
    /** Asks for the paper (unless the note already has one) and inserts a blank page after {@code afterIndex}. */
    private void choosePageToInsert(int afterIndex){
        if(activeSession==null)return;final DocumentSession session=activeSession;
        if(!library.managed(session.uri)){toast("문서함에 저장한 뒤 페이지를 추가하세요");return;}
        // default: a page like the one before it (same size, orientation and paper); "다른 형식으로 페이지 추가" offers other papers and sizes
        NotebookFiles.Paper same=library.paper(new File(session.uri.getPath()));insertPage(session,same!=null?same:new NotebookFiles.Paper(0,Color.WHITE),afterIndex,0);
    }
    /** Lets the user pick another paper and size for the new page (default: same size as the previous page). */
    private void chooseOtherPageFormat(int afterIndex){
        if(activeSession==null)return;final DocumentSession session=activeSession;
        if(!library.managed(session.uri)){toast("문서함에 저장한 뒤 페이지를 추가하세요");return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        PaperChoiceView paper=new PaperChoiceView(this);paper.onTemplateRequest(()->requestTemplate(paper));box.addView(paper,new LinearLayout.LayoutParams(-1,-2));
        RadioGroup sizes=new RadioGroup(this);sizes.setPadding(dp(22),dp(4),dp(18),dp(4));String[] names={"이전 페이지와 같은 크기·방향","A4 세로","A4 가로"};
        for(int i=0;i<names.length;i++){RadioButton b=new RadioButton(this);b.setText(names[i]);b.setId(100+i);b.setTextSize(15);sizes.addView(b);}sizes.check(100);box.addView(sizes,new LinearLayout.LayoutParams(-1,-2));
        TextView note=new TextView(this);note.setText("서식 PDF를 고르면 그 서식의 크기를 따릅니다");note.setTextSize(12);note.setTextColor(0xFF8E8E93);note.setPadding(dp(24),0,dp(18),dp(6));box.addView(note);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("다른 형식으로 추가 · p."+(afterIndex+1)+" 뒤").setView(scroll).setPositiveButton("추가",(d,w)->{try{insertPage(session,paper.paper(),afterIndex,sizes.getCheckedRadioButtonId()-100);}catch(IllegalArgumentException error){toast(error.getMessage());}}).setNegativeButton("취소",null).show();
    }
    private void insertPage(DocumentSession session,NotebookFiles.Paper paper,int afterIndex){insertPage(session,paper,afterIndex,0);}
    private void insertPage(DocumentSession session,NotebookFiles.Paper paper,int afterIndex,int sizeMode){
        final File file=new File(session.uri.getPath());
        modifyPages(session,"새 페이지를 추가하는 중…",()->library.insertPage(file,paper,afterIndex,sizeMode),()->session.store.insertPageAfter(afterIndex),afterIndex+1,"페이지 추가 실패");
    }
    private void confirmDeletePage(int index){
        if(activeSession==null||renderer==null)return;final DocumentSession session=activeSession;
        if(!library.managed(session.uri)){toast("문서함에 저장한 뒤 페이지를 삭제하세요");return;}
        if(renderer.getPageCount()<=1){toast("마지막 한 페이지는 삭제할 수 없습니다");return;}
        new AlertDialog.Builder(this).setTitle("페이지 "+(index+1)+" 삭제").setMessage("이 페이지와 그 위의 필기·메모·하이라이트·즐겨찾기가 함께 삭제되며 되돌릴 수 없습니다.").setPositiveButton("삭제",(d,w)->deletePage(session,index)).setNegativeButton("취소",null).show();
    }
    private void deletePage(DocumentSession session,int index){
        final File file=new File(session.uri.getPath());
        modifyPages(session,"페이지를 삭제하는 중…",()->library.deletePage(file,index),()->session.store.removePage(index),index,"페이지 삭제 실패");
    }
    /** Page menu of the preview sidebar: add after / delete. */
    private void showPageMenu(int page,View anchor){
        if(renderer==null)return;
        AnchoredMenu.showRightOf(this,anchor,sidePanel,AnchoredMenu.rows(
            new AnchoredMenu.Row("이 페이지로 이동",R.drawable.ic_page,()->showPage(page)).tint(0xFF30B0C7),
            new AnchoredMenu.Row("뒤에 페이지 추가",R.drawable.ic_page_add,()->choosePageToInsert(page)).tint(0xFF34C759),
            new AnchoredMenu.Row("다른 형식으로 뒤에 추가",R.drawable.ic_page_add,()->chooseOtherPageFormat(page)).tint(0xFF34C759),
            new AnchoredMenu.Row("이 페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(page)).danger()));
    }
    /** Runs a file-level page edit off the UI thread, then re-opens the renderer and shifts the annotations to match. */
    private void modifyPages(final DocumentSession session,String message,java.util.concurrent.Callable<Integer> operation,Runnable annotations,int target,String failure){
        if(!appending.add(session))return;onSelectionAdjustStarted();session.store.save();
        final ProgressDialog progress=ProgressDialog.show(this,"",message,true,false);final File file=new File(session.uri.getPath());
        new Thread(()->{try{final int count=operation.call();runOnUiThread(()->{appending.remove(session);if(isFinishing()||isDestroyed())return;progress.dismiss();if(!sessions.contains(session))return;try{
            ParcelFileDescriptor nextDescriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer nextRenderer;try{nextRenderer=new PdfRenderer(nextDescriptor);}catch(Exception error){nextDescriptor.close();throw error;}
            annotations.run();session.store.save();session.renderer.close();try{session.descriptor.close();}catch(IOException ignored){}session.renderer=nextRenderer;session.descriptor=nextDescriptor;session.redoStrokes.clear();session.textRegions.clear();
            int page=Math.max(0,Math.min(target,count-1));session.page=page;
            if(session==activeSession){renderer=nextRenderer;descriptor=nextDescriptor;showPage(page);rebuildThumbnails();}saveSessionState();
        }catch(Exception error){toast("페이지를 다시 열 수 없습니다: "+error.getMessage());}});}catch(Exception error){runOnUiThread(()->{appending.remove(session);if(isFinishing()||isDestroyed())return;progress.dismiss();toast(failure+": "+error.getMessage());});}},"page-edit").start();
    }
    private void libraryChanged(File before,File after){
        Uri oldUri=Uri.fromFile(before),newUri=Uri.fromFile(after);for(DocumentSession session:sessions)if(session.uri.equals(oldUri)){session.uri=newUri;session.title=after.getName();session.store.rebind(newUri);if(session==activeSession){documentUri=newUri;documentTitle=session.title;titleView.setText(documentTitle);}}
        if(recentPrefs.getString("last_uri","").equals(oldUri.toString()))recentPrefs.edit().putString("last_uri",newUri.toString()).putString("last_title",after.getName()).apply();updateTabs();saveSessionState();
    }
    // ---- floating menus: bottom bar as a draggable pill, fullscreen toolbar that stays open
    /** Where the tool bar lives: "bottom" (docked under the page), "float" (draggable pill, horizontal or vertical), "left" / "right" (docked as a vertical rail). */
    private String barPlace(){if(recentPrefs==null)return "bottom";String p=recentPrefs.getString("bar_place",null);if(p==null)return recentPrefs.getBoolean("float_bar",false)?"float":"bottom";return p;}
    private boolean barVertical(){String p=barPlace();return p.equals("left")||p.equals("right")||(p.equals("float")&&recentPrefs.getBoolean("bar_vertical",false));}
    private boolean floatBar(){return !barPlace().equals("bottom");}
    private void setBarPlace(String place){recentPrefs.edit().putString("bar_place",place).apply();applyBarMode();}
    /** The bar's own layout menu (opened from the ⠿ button at its end): bottom / floating / left / right and horizontal / vertical. */
    private void showBarLayoutMenu(View anchor){
        String place=barPlace();List<AnchoredMenu.Row> rows=new ArrayList<>();
        rows.add(new AnchoredMenu.Row("아래에 고정",R.drawable.ic_float,()->setBarPlace("bottom")).tint(0xFF007AFF).selected(place.equals("bottom")));
        rows.add(new AnchoredMenu.Row("화면 위에 띄우기 (끌어서 이동)",R.drawable.ic_float,()->setBarPlace("float")).tint(0xFF007AFF).selected(place.equals("float")));
        rows.add(new AnchoredMenu.Row("왼쪽 옆에 고정 (세로)",R.drawable.ic_float,()->setBarPlace("left")).tint(0xFF007AFF).selected(place.equals("left")));
        rows.add(new AnchoredMenu.Row("오른쪽 옆에 고정 (세로)",R.drawable.ic_float,()->setBarPlace("right")).tint(0xFF007AFF).selected(place.equals("right")));
        if(place.equals("float")){rows.add(AnchoredMenu.Row.divider());boolean v=recentPrefs.getBoolean("bar_vertical",false);
            rows.add(new AnchoredMenu.Row("가로 방향",R.drawable.ic_float,()->{recentPrefs.edit().putBoolean("bar_vertical",false).apply();applyBarMode();}).tint(0xFF8E8E93).selected(!v));
            rows.add(new AnchoredMenu.Row("세로 방향",R.drawable.ic_float,()->{recentPrefs.edit().putBoolean("bar_vertical",true).apply();applyBarMode();}).tint(0xFF8E8E93).selected(v));}
        rows.add(AnchoredMenu.Row.divider());
        rows.add(new AnchoredMenu.Row("필기 도구 줄 항상 보이기",R.drawable.ic_ink,()->setWriteStripShown(!writeStripShown())).tint(0xFF5856D6).selected(writeStripShown()));
        AnchoredMenu.show(this,anchor,place.equals("bottom")||(place.equals("float")&&!barVertical()),rows,null);
    }
    /** Switches the bar (and its read / write rows) between a horizontal row and a vertical column. */
    private void setBarOrientation(boolean vertical){
        bottomBar.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);bottomBar.setGravity(vertical?Gravity.CENTER_HORIZONTAL:Gravity.CENTER_VERTICAL);
        for(LinearLayout row:new LinearLayout[]{readBar}){
            row.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);row.setGravity(vertical?Gravity.CENTER_HORIZONTAL:Gravity.CENTER_VERTICAL);
            for(int i=0;i<row.getChildCount();i++){View c=row.getChildAt(i);LinearLayout.LayoutParams p;
                if(c==pageLabel){p=vertical?new LinearLayout.LayoutParams(dp(44),dp(30)):new LinearLayout.LayoutParams(dp(64),dp(32));if(vertical)p.setMargins(0,0,0,dp(4));else p.setMargins(0,0,dp(5),0);pageLabel.setTextSize(vertical?9:11);}
                else{p=vertical?new LinearLayout.LayoutParams(dp(44),0,1):new LinearLayout.LayoutParams(0,dp(44),1);if(vertical)p.setMargins(0,dp(1),0,dp(1));else p.setMargins(dp(2),0,dp(2),0);}
                c.setLayoutParams(p);}
            LinearLayout.LayoutParams rp=vertical?new LinearLayout.LayoutParams(-1,0,1):new LinearLayout.LayoutParams(0,-1,1);row.setLayoutParams(rp);}
        if(barGrip!=null)barGrip.setLayoutParams(vertical?new LinearLayout.LayoutParams(dp(44),dp(18)):new LinearLayout.LayoutParams(dp(18),dp(44)));
        if(barMenuButton!=null)barMenuButton.setLayoutParams(vertical?new LinearLayout.LayoutParams(dp(44),dp(38)):new LinearLayout.LayoutParams(dp(38),dp(44)));
    }
    private ImageButton barMenuButton;private int insetTop;
    private ImageButton readButton,insertButton; private View paneFrame;
    /** A docked side rail takes its width from the page area (not in full screen). */
    private void railMargin(){if(contentColumn==null)return;FrameLayout.LayoutParams cl=(FrameLayout.LayoutParams)contentColumn.getLayoutParams();String p=barPlace();int rail=fullscreen?0:dp(56);cl.leftMargin=p.equals("left")?rail:0;cl.rightMargin=p.equals("right")?rail:0;contentColumn.setLayoutParams(cl);}
    private boolean dockPinned(){return recentPrefs!=null&&recentPrefs.getBoolean("dock_pinned",false);}
    private void toggleDockPinned(){boolean on=!dockPinned();recentPrefs.edit().putBoolean("dock_pinned",on).apply();if(fullscreen){if(on)showFullscreenDock(false);else{fullscreenDock.setTranslationX(0);fullscreenDock.setTranslationY(0);dockGrip.setVisibility(View.GONE);fullscreenDock.removeCallbacks(dockHider);fullscreenDock.postDelayed(dockHider,3500);}}toast(on?"전체 화면에서도 도구 모음이 계속 떠 있습니다 (왼쪽 ⋮⋮를 끌어 옮기기)":"전체 화면 도구 모음이 자동으로 숨습니다");}
    private void applyBarMode(){
        String place=barPlace();boolean vertical=barVertical(),overlay=!place.equals("bottom");
        ViewGroup parent=(ViewGroup)bottomBar.getParent();if(parent!=null)parent.removeView(bottomBar);
        bottomBar.setTranslationX(0);bottomBar.setTranslationY(0);setBarOrientation(vertical);
        FrameLayout.LayoutParams cl=(FrameLayout.LayoutParams)contentColumn.getLayoutParams();cl.leftMargin=0;cl.rightMargin=0;
        int rail=dp(56);
        if(!overlay){bottomBar.setBackground(barSurface);bottomBar.setElevation(dp(8));barGrip.setVisibility(View.GONE);bottomBar.setPadding(dp(6),0,dp(6),insetBottom);contentColumn.addView(bottomBar,new LinearLayout.LayoutParams(-1,dp(54)+insetBottom));}
        else if(place.equals("float")){
            bottomBar.setBackground(round(0xF8FFFFFF,27));bottomBar.setElevation(dp(10));barGrip.setVisibility(View.VISIBLE);
            if(!vertical){bottomBar.setPadding(dp(8),0,dp(8),0);int w=Math.min(dp(480),getResources().getDisplayMetrics().widthPixels-dp(24));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,dp(54),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.bottomMargin=dp(14)+insetBottom;root.addView(bottomBar,lp);applyFloatPos(bottomBar,"bar");}
            else{bottomBar.setPadding(0,dp(6),0,dp(6));int h=Math.min(dp(560),getResources().getDisplayMetrics().heightPixels-dp(200));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(54),h,Gravity.CENTER_VERTICAL|Gravity.START);lp.leftMargin=dp(8);root.addView(bottomBar,lp);applyFloatPos(bottomBar,"barv");}
        }else{
            bottomBar.setBackground(barSurface);bottomBar.setElevation(dp(8));barGrip.setVisibility(View.GONE);bottomBar.setPadding(dp(4),insetTop+dp(4),dp(4),insetBottom+dp(4));
            root.addView(bottomBar,new FrameLayout.LayoutParams(rail,-1,place.equals("left")?Gravity.START:Gravity.END));
        }
        contentColumn.setLayoutParams(cl);railMargin();
        if(stripBox!=null)applyFloatPos(stripBox,"strip");   // the bar moved: keep the pen strip clear of it
        enforceChrome();
    }
    /** Keeps a floating bar inside the view it floats in (the pen strip lives in the page area, so it can never slip behind the bottom bar) and above a floating bottom bar. */
    private void moveFloating(View target,float tx,float ty){
        View area=target.getParent() instanceof View?(View)target.getParent():root;int w=area.getWidth(),h=area.getHeight();if(w<=0||h<=0){target.setTranslationX(tx);target.setTranslationY(ty);return;}
        float maxY=h-target.getBottom();
        if(target==stripBox&&bottomBar!=null&&bottomBar.getParent()==root&&bottomBar.getVisibility()==View.VISIBLE&&barPlace().equals("float")&&!barVertical()){   // a floating bottom bar overlaps the page: stop above it
            int[] a=new int[2],b=new int[2];area.getLocationInWindow(a);bottomBar.getLocationInWindow(b);float barTop=b[1]-a[1];if(barTop>0)maxY=Math.min(maxY,barTop-dp(6)-target.getBottom());
        }
        target.setTranslationX(Math.max(-target.getLeft(),Math.min(tx,w-target.getRight())));target.setTranslationY(Math.max(-target.getTop(),Math.min(ty,maxY)));
    }
    private void applyFloatPos(final View target,final String key){target.post(()->moveFloating(target,dp(1)*recentPrefs.getFloat(key+"_dx",0f),dp(1)*recentPrefs.getFloat(key+"_dy",0f)));}
    private View makeGrip(final View target,final String key){
        View grip=new View(this){private final Paint dots=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){dots.setColor(0xFFB0B0B8);float cx=getWidth()/2f,cy=getHeight()/2f;for(int i=-1;i<=1;i++)for(int j=0;j<2;j++)c.drawCircle(cx+(j-0.5f)*dp(5),cy+i*dp(6),1.6f*getResources().getDisplayMetrics().density,dots);}};
        grip.setContentDescription("메뉴 위치 이동 · 끌어서 옮기기");final float[] d=new float[4];
        grip.setOnTouchListener((v,e)->{switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:d[0]=e.getRawX();d[1]=e.getRawY();d[2]=target.getTranslationX();d[3]=target.getTranslationY();if(v.getParent()!=null)v.getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:moveFloating(target,d[2]+e.getRawX()-d[0],d[3]+e.getRawY()-d[1]);return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:{String k=target==bottomBar&&barVertical()?"barv":key;recentPrefs.edit().putFloat(k+"_dx",target.getTranslationX()/dp(1)).putFloat(k+"_dy",target.getTranslationY()/dp(1)).apply();return true;}}return false;});
        return grip;
    }
    // ---- rename right in the title bar
    private void beginTitleEdit(){
        final DocumentSession session=activeSession;if(session==null||titleEdit!=null)return;
        if(!library.managed(session.uri)){toast("문서함에 저장한 뒤 이름을 변경하세요");return;}
        onSelectionAdjustStarted();session.store.save();
        final EditText e=new EditText(this);titleEdit=e;e.setSingleLine();e.setTextSize(15);e.setTextColor(NAVY);e.setTypeface(Typeface.DEFAULT_BOLD);e.setBackground(round(Color.WHITE,18));e.setPadding(dp(14),0,dp(14),0);e.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);e.setTag("document_title_edit");
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);e.setText(session.title.replaceFirst("(?i)\\.pdf$",""));e.selectAll();
        int index=header.indexOfChild(titleView);int max=Math.max(dp(120),Math.min(header.getWidth()-dp(256),Math.round(header.getWidth()*.6f)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(max,dp(36));lp.setMargins(dp(6),0,dp(6),0);titleView.setVisibility(View.GONE);header.addView(e,index,lp);
        e.setOnEditorActionListener((v,action,event)->{commitTitleEdit(session,true);return true;});
        e.setOnFocusChangeListener((v,has)->{if(!has)commitTitleEdit(session,true);});
        e.requestFocus();InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(e,InputMethodManager.SHOW_IMPLICIT);
    }
    private void commitTitleEdit(final DocumentSession session,boolean save){
        if(titleEdit==null)return;EditText e=titleEdit;titleEdit=null;String text=e.getText().toString().trim();
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(e.getWindowToken(),0);
        header.removeView(e);titleView.setVisibility(View.VISIBLE);
        if(!save||text.isEmpty()||text.equals(session.title.replaceFirst("(?i)\\.pdf$",""))||!sessions.contains(session))return;
        final File before=new File(session.uri.getPath());
        new Thread(()->{try{String name=NotebookFiles.pdfName(text);File after=library.transfer(before,before.getParentFile(),name,true);runOnUiThread(()->libraryChanged(before,after));}catch(Exception error){runOnUiThread(()->toast("이름을 바꿀 수 없습니다: "+error.getMessage()));}},"rename-pdf").start();
    }
    private void renameDocument(DocumentSession session){
        if(!library.managed(session.uri)){toast("문서함에 저장한 뒤 이름을 변경하세요");return;}onSelectionAdjustStarted();session.store.save();File before=new File(session.uri.getPath());EditText input=new EditText(this);input.setSingleLine();input.setText(session.title.replaceFirst("(?i)\\.pdf$",""));input.selectAll();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("이름 변경").setView(input).setPositiveButton("저장",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String name=NotebookFiles.pdfName(input.getText().toString());dialog.getButton(-1).setEnabled(false);new Thread(()->{try{File after=library.transfer(before,before.getParentFile(),name,true);runOnUiThread(()->{dialog.dismiss();libraryChanged(before,after);});}catch(Exception error){runOnUiThread(()->{dialog.getButton(-1).setEnabled(true);input.setError(error.getMessage());});}},"rename-pdf").start();}catch(Exception error){input.setError(error.getMessage());}}));dialog.show();
    }
    /** Saves the untouched source file (no ink, notes or other annotations) wherever the user picks. */
    private void exportOriginal(){
        if(activeSession==null||documentUri==null){toast("문서를 먼저 여세요");return;}
        String mime=getContentResolver().getType(documentUri);if(mime==null||mime.isEmpty())mime="application/octet-stream";
        exportOriginalSource=documentUri;
        try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,documentTitle),EXPORT_ORIGINAL);}catch(RuntimeException error){toast("저장 위치를 열 수 없습니다");}
    }
    private Uri exportOriginalSource;
    private void receiveOriginalExport(int result,Intent data){
        final Uri source=exportOriginalSource;exportOriginalSource=null;if(result!=RESULT_OK||data==null||data.getData()==null||source==null)return;final Uri target=data.getData();
        new Thread(()->{boolean ok=false;try(java.io.InputStream in=getContentResolver().openInputStream(source);java.io.OutputStream out=getContentResolver().openOutputStream(target,"wt")){byte[] buffer=new byte[1<<16];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);ok=true;}catch(Exception ignored){}final boolean done=ok;runOnUiThread(()->toast(done?"원본 파일을 내보냈습니다":"원본 내보내기에 실패했습니다"));},"export-original").start();
    }
    /** Prints the document together with its ink, notes and typed text through the system print dialog (which also offers "Save as PDF"). */
    private void printDocument(){
        if(activeSession==null||renderer==null||documentUri==null){toast("문서를 먼저 여세요");return;}
        final Uri source=activeSession.officePreview==null?documentUri:Uri.fromFile(activeSession.officePreview);final String snapshot;final int count=renderer.getPageCount();final String title=documentTitle.replaceAll("(?i)\\.[^.]+$","");
        try{snapshot=store.exportJson(documentUri,documentTitle);}catch(JSONException error){toast("인쇄 준비 실패");return;}
        final ProgressDialog progress=ProgressDialog.show(this,"인쇄","인쇄할 문서를 준비하는 중입니다…",true,false);
        new Thread(()->{try{File dir=new File(getCacheDir(),"print");dir.mkdirs();final File file=new File(dir,"print.pdf");
            try(OutputStream out=new FileOutputStream(file)){AnnotationStore annotations=new AnnotationStore(this);annotations.importJson(snapshot,count);DocumentExporter.export(this,source,annotations,out);}
            runOnUiThread(()->{progress.dismiss();android.print.PrintManager manager=(android.print.PrintManager)getSystemService(PRINT_SERVICE);if(manager==null){toast("이 기기에서는 인쇄를 지원하지 않습니다");return;}
                manager.print(title,new android.print.PrintDocumentAdapter(){
                    @Override public void onLayout(android.print.PrintAttributes oldAttributes,android.print.PrintAttributes newAttributes,android.os.CancellationSignal signal,LayoutResultCallback callback,Bundle extras){if(signal.isCanceled()){callback.onLayoutCancelled();return;}callback.onLayoutFinished(new android.print.PrintDocumentInfo.Builder(title+".pdf").setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build(),true);}
                    @Override public void onWrite(android.print.PageRange[] pages,ParcelFileDescriptor destination,android.os.CancellationSignal signal,WriteResultCallback callback){try(InputStream in=new FileInputStream(file);OutputStream out=new FileOutputStream(destination.getFileDescriptor())){byte[] buffer=new byte[1<<16];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});}catch(IOException error){callback.onWriteFailed(error.getMessage());}}
                },null);});
        }catch(Exception error){runOnUiThread(()->{progress.dismiss();toast("인쇄 준비 실패: "+error.getMessage());});}},"print-prepare").start();
    }
    private void exportPdf(){if(activeSession==null)return;try{exportSource=activeSession.officePreview==null?documentUri:Uri.fromFile(activeSession.officePreview);exportSnapshot=store.exportJson(documentUri,documentTitle);exportPageCount=renderer.getPageCount();startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE,documentTitle.replaceAll("(?i)\\.pdf$","")+"_notes.pdf"),EXPORT_PDF);}catch(JSONException error){toast("PDF 준비 실패");}}
    private void receivePdfExport(int result,Intent data){if(result!=RESULT_OK||data==null||data.getData()==null)return;final Uri source=exportSource,target=data.getData();final String snapshot=exportSnapshot;final int count=exportPageCount;if(source==null||snapshot==null){toast("다시 내보내세요");return;}ProgressDialog progress=ProgressDialog.show(this,"PDF 내보내기","필기·타이핑·이미지를 PDF에 담는 중입니다…",true,false);new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("출력 파일을 열 수 없습니다");AnnotationStore annotations=new AnnotationStore(this);annotations.importJson(snapshot,count);DocumentExporter.export(this,source,annotations,out);runOnUiThread(()->{progress.dismiss();toast("PDF를 내보냈습니다");});}catch(Exception error){runOnUiThread(()->{progress.dismiss();toast("PDF 내보내기 실패: "+error.getMessage());});}},"pdf-export").start();}
    private String placementText="";private float placementRot;
    private void placeElement(String kind,String asset){
        float[] t=freshDrop();
        if(renderer!=null&&t!=null&&!kind.equals("text")){
            if(!kind.equals("sticker")&&!kind.equals("video")&&!kind.equals("shape")&&!kind.equals("table")&&!kind.equals("youtube"))placementText="";
            stopInk();highlightMode=outlineMode=false;placementKind=kind;placementAsset=asset;createPlacedElement((int)t[0],t[1],t[2]);return;
        }
        placeElementArmed(kind,asset);
    }
    private float[] freshDrop(){float[] t=dropTarget;dropTarget=null;return t!=null&&System.currentTimeMillis()-dropTime<=90000?t:null;}
    private void placeElementArmed(String kind,String asset){if(renderer==null){toast("문서를 먼저 여세요");return;}stopInk();highlightMode=outlineMode=false;memoMode=true;placementKind=kind;placementAsset=asset;if(!kind.equals("sticker")&&!kind.equals("video")&&!kind.equals("shape")&&!kind.equals("table")&&!kind.equals("youtube"))placementText="";pageView.setHighlightMode(false,selectedColor);pageView.setOutlineMode(false);pageView.setMemoMode(true);updateToolStates();toast(kind.equals("text")?"글을 넣을 위치를 탭하세요. 이미 쓴 글은 탭하면 수정합니다. 끝나면 ‘텍스트’ 버튼을 다시 누르세요":"넣을 위치를 터치하세요");}
    private void createPlacedElement(int page,float x,float y){
        if("text".equals(placementKind)){
            commitInlineText();
            AnnotationStore.PageElement existing=elementAt(page,x,y);if(existing!=null){beginInlineText(existing,false);return;}
            beginInlineText(newTextBox(page,x,y),true);return;
        }
        AnnotationStore.PageElement element=new AnnotationStore.PageElement();element.page=page;element.kind=placementKind;element.asset=placementAsset;element.text=placementText;placementText="";element.rot=placementRot;placementRot=0f;
        RectF pr=pageView.pageRect();float pageRatio=pr.height()>0?pr.width()/pr.height():.707f;String kind=element.kind;
        if(kind.equals("image")||kind.equals("video")||kind.equals("sticker")||kind.equals("youtube")){
            float ratio=kind.equals("youtube")?16f/9f:1f;if(!kind.equals("sticker")&&!kind.equals("youtube")){File file=AnnotationPainter.builtinAsset(this,element.asset);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);if(o.outWidth>0&&o.outHeight>0)ratio=o.outWidth/(float)o.outHeight;}
            float w=kind.equals("sticker")?.16f:element.asset.startsWith("tape-")?.32f:.5f,h=w*pageRatio/ratio;if(h>.6f){h=.6f;w=h*ratio/pageRatio;}
            element.left=Math.max(0f,Math.min(1f-w,x-w/2));element.top=Math.max(0f,Math.min(1f-h,y-h/2));element.right=element.left+w;element.bottom=element.top+h;
            placementKind="";memoMode=false;pageView.setMemoMode(false);updateToolStates();store.elements.add(element);store.save();pageView.selectElement(element);toast("모서리를 끌어 크기를, 본문을 끌어 위치를 바꿉니다");return;
        }
        if(kind.equals("shape")||kind.equals("table")){
            boolean linear=kind.equals("shape")&&(element.text.startsWith("line")||element.text.startsWith("arrow"));
            float w=kind.equals("table")?.7f:linear?.4f:.3f,h=kind.equals("table")?Math.max(.08f,Shapes.Table.parse(element.text).rows*.045f):linear?.05f:w*pageRatio;if(h>.7f)h=.7f;
            element.left=Math.max(0f,Math.min(1f-w,x-w/2));element.top=Math.max(0f,Math.min(1f-h,y-h/2));element.right=element.left+w;element.bottom=element.top+h;
            placementKind="";memoMode=false;pageView.setMemoMode(false);updateToolStates();store.elements.add(element);store.save();pageView.selectElement(element);toast("모서리를 끌어 크기를, 본문을 끌어 위치를 바꿉니다. 한 번 더 탭하면 색·내용 메뉴");return;
        }
        element.left=Math.min(.75f,x);element.top=Math.min(.8f,y);element.right=Math.min(.97f,element.left+.6f);element.bottom=Math.min(.98f,element.top+.07f);placementKind="";memoMode=false;pageView.setMemoMode(false);updateToolStates();editPageElement(element,true);}
    private void editPageElement(AnnotationStore.PageElement element,boolean fresh){if("text".equals(element.kind)){beginInlineText(element,fresh);return;}final AnnotationStore target=store;EditText input=new EditText(this);input.setText(element.text);input.setHint(element.kind.equals("link")?"https://… 또는 YouTube 주소":"타이핑할 내용");input.setMinLines(3);new AlertDialog.Builder(this).setTitle(element.kind.equals("link")?"웹·유튜브 링크":"타이핑").setView(input).setPositiveButton("저장",(d,w)->{String text=input.getText().toString().trim();if(text.isEmpty())return;if(element.kind.equals("link")&&!validWebUrl(text)){toast("http 또는 https 주소를 입력하세요");return;}element.text=text;if(fresh)target.elements.add(element);target.save();pageView.invalidate();}).setNegativeButton("취소",null).setNeutralButton(fresh?"닫기":"삭제",(d,w)->{if(!fresh){target.elements.remove(element);target.save();pageView.invalidate();}}).show();}
    private boolean validWebUrl(String value){Uri uri=Uri.parse(value);return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null;}
    @Override public void onElementTapped(AnnotationStore.PageElement element){
        if("audio".equals(element.kind)){showAudioPlayer(element);return;}
        if("text".equals(element.kind)){beginInlineText(element,false);return;}
        if("hyperlink".equals(element.kind)){openHyperlink(element);return;}
        if("link".equals(element.kind)&&youtubeId(element.text)!=null){element.kind="youtube";element.text=youtubeId(element.text);store.save();}
        String kind=element.kind;
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        if(kind.equals("link"))rows.add(new AnchoredMenu.Row("링크 열기",R.drawable.ic_link,()->{if(validWebUrl(element.text))try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(element.text)));}catch(ActivityNotFoundException error){toast("링크를 열 앱이 없습니다");}}).tint(0xFF5856D6));
        if(kind.equals("video")){rows.add(new AnchoredMenu.Row("여기서 재생",R.drawable.ic_video,()->playInline(element)).tint(0xFFFF3B30));rows.add(new AnchoredMenu.Row("크게 보기",R.drawable.ic_fullscreen,()->showVideoPlayer(element)).tint(0xFF8E8E93));rows.add(new AnchoredMenu.Row("다른 앱으로 재생",R.drawable.ic_share,()->openVideoExternally(new File(new File(getFilesDir(),"videos"),element.text))).tint(0xFF8E8E93));}
        if(kind.equals("youtube")){rows.add(new AnchoredMenu.Row("여기서 재생",R.drawable.ic_youtube,()->playInline(element)).tint(0xFFFF0000));rows.add(new AnchoredMenu.Row("유튜브 앱에서 열기",R.drawable.ic_link,()->openYoutube(element.text)).tint(0xFF8E8E93));}
        if(kind.equals("shape"))rows.add(new AnchoredMenu.Row("색·선 굵기",R.drawable.ic_palette,()->showShapeDialog(element)).tint(0xFFAF52DE));
        if(kind.equals("table")){rows.add(new AnchoredMenu.Row("셀 내용 편집",R.drawable.ic_table,()->editTableCells(element)).tint(0xFF30B0C7));rows.add(new AnchoredMenu.Row("행·열·색상",R.drawable.ic_sliders,()->showTableDialog(element)).tint(0xFFAF52DE));}
        if(kind.equals("link")||!(kind.equals("youtube")||kind.equals("shape")||kind.equals("table")||kind.equals("image")||kind.equals("sticker")||kind.equals("video")))rows.add(new AnchoredMenu.Row("수정",R.drawable.ic_compose,()->editPageElement(element,false)).tint(0xFF007AFF));
        rows.add(new AnchoredMenu.Row("위치·크기",R.drawable.ic_fullscreen,()->editElementGeometry(element)).tint(0xFF34C759));
        if(PdfPageView.selectable(element)&&AnnotationPainter.rotates(element))rows.add(AnchoredMenu.Row.custom(opacityRow(element)));
        rows.add(new AnchoredMenu.Row("삭제",R.drawable.ic_delete,()->deleteElement(element)).danger());
        AnchoredMenu.showCentered(this,root,"페이지 "+(element.page+1),rows);
    }
    /** An inline slider inside the element menu: drag to fade a picture, sticker or shape (10–100 %); the page repaints live. */
    private View opacityRow(AnnotationStore.PageElement element){
        final AnnotationStore target=store;LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(8),dp(14),dp(6));box.setTag("opacity_row");
        final TextView label=new TextView(this);label.setTextSize(13);label.setTextColor(0xFF636366);label.setText("투명도  "+Math.round(element.alpha*100)+"%");box.addView(label);
        final android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(90);bar.setProgress(Math.max(0,Math.min(90,Math.round(element.alpha*100)-10)));bar.setTag("opacity_bar");bar.setContentDescription("투명도 조절");bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){element.alpha=(value+10)/100f;label.setText("투명도  "+(value+10)+"%");if(pageView!=null)pageView.invalidate();redrawPages();}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b){if(target!=null)target.save();}});
        box.addView(bar,new LinearLayout.LayoutParams(-1,dp(36)));return box;
    }
    private void deleteElement(AnnotationStore.PageElement element){
        if(store==null)return;
        if(element.kind.equals("hyperlink")){store.elements.removeIf(e->e.kind.equals("hyperlink")&&e.page==element.page&&e.color==element.color&&e.text.equals(element.text));}
        else store.elements.remove(element);
        if(element.kind.equals("video")){File video=new File(new File(getFilesDir(),"videos"),element.text);video.delete();}
        store.save();pageView.selectElement(null);redrawPages();if(sidebarVisible&&panelTab==4)rebuildInsertions();
    }
    // ---- pictures, stickers, videos
    /** "사진·이미지": a small menu card first, so the person can back out (tap outside) before any system file picker opens. */
    private void insertImage(){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        boolean clip=false;try{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=clipboard==null?null:clipboard.getPrimaryClip();Uri uri=data!=null&&data.getItemCount()>0?data.getItemAt(0).getUri():null;clip=uri!=null&&"content".equals(uri.getScheme())&&data.getDescription()!=null&&data.getDescription().hasMimeType("image/*");}catch(RuntimeException ignored){}
        final Uri clipUri;{Uri u=null;try{ClipData d=((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();if(clip&&d!=null)u=d.getItemAt(0).getUri();}catch(RuntimeException ignored){}clipUri=u;}
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        rows.add(new AnchoredMenu.Row("사진 보관함에서 선택",R.drawable.ic_image,this::pickPhoto).tint(0xFF007AFF));
        rows.add(new AnchoredMenu.Row("파일·드라이브에서 선택",R.drawable.ic_folder_open,this::pickImage).tint(0xFFFF9500));
        if(clipUri!=null)rows.add(new AnchoredMenu.Row("복사한 이미지 붙여넣기",R.drawable.ic_paste,()->importImage(clipUri)).tint(0xFF34C759));
        AnchoredMenu.showCentered(this,root,"사진·이미지 넣기",rows);
    }
    /** The system photo picker (Android 13+, has its own close button); older versions use the gallery. */
    private void pickPhoto(){if(renderer==null)return;try{Intent pick=Build.VERSION.SDK_INT>=33?new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES):new Intent(Intent.ACTION_PICK,android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*");startActivityForResult(pick,IMPORT_IMAGE);}catch(RuntimeException error){pickImage();}}
    private void pickImage(){if(renderer==null){toast("문서를 먼저 여세요");return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),IMPORT_IMAGE);}
    private void pickVideo(){if(renderer==null){toast("문서를 먼저 여세요");return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","application/vnd.ms-asf","application/x-msvideo","application/x-matroska","application/x-mpegurl"}),IMPORT_VIDEO);}
    private static final String[][] STICKER_GROUPS={
        {"⭐","🌟","✨","❤️","🧡","💛","💚","💙","💜","🖤","💖","💯"},
        {"🍂","🍁","🍃","🌿","🍀","🌸","🌼","🌻","🌹","🌷","🌲","🌵"},
        {"👍","👏","🙏","💪","👀","🎉","🎈","🎁","🏆","🥇","🎯","🚩"},
        {"💡","📌","📍","✅","❗","❓","⚠️","🔥","📝","🔖","💬","🔎"},
        {"😀","😍","😎","🤔","😮","😢","😴","🥳","🐶","🐱","🦋","🌈"},
        {"☀️","🌙","☁️","⚡","❄️","☕","🍎","🍰","📚","⏰","🎵","✈️"}};
    private static final String[] STICKER_TITLES={"별·하트","낙엽·꽃","응원·표시","메모용","표정·동물","날씨·생활"};
    private void showStickerPicker(){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        final AlertDialog[] holder=new AlertDialog[1];
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(4),dp(8),dp(4));
        for(int g=0;g<STICKER_GROUPS.length;g++){
            TextView title=new TextView(this);title.setText(STICKER_TITLES[g]);title.setTextSize(12);title.setTextColor(0xFF8E8E93);title.setPadding(dp(6),dp(8),0,dp(2));box.addView(title);
            String[] group=STICKER_GROUPS[g];LinearLayout rowView=null;
            for(int i=0;i<group.length;i++){
                if(i%6==0){rowView=new LinearLayout(this);box.addView(rowView,new LinearLayout.LayoutParams(-1,dp(50)));}
                final String sticker=group[i];TextView cell=new TextView(this);cell.setText(sticker);cell.setTextSize(27);cell.setGravity(Gravity.CENTER);cell.setContentDescription("스티커 "+sticker);
                cell.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();placementText=sticker;placeElement("sticker","");});
                rowView.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
            }
        }
        TextView tapeTitle=new TextView(this);tapeTitle.setText("마스킹 테이프");tapeTitle.setTextSize(12);tapeTitle.setTextColor(0xFF8E8E93);tapeTitle.setPadding(dp(6),dp(12),0,dp(4));box.addView(tapeTitle);
        LinearLayout tapeRow=null;for(int i=1;i<=16;i++){
            if((i-1)%4==0){tapeRow=new LinearLayout(this);box.addView(tapeRow,new LinearLayout.LayoutParams(-1,dp(44)));}
            final String name="tape-"+(i<10?"0":"")+i+".png";ImageView tape=new ImageView(this);tape.setScaleType(ImageView.ScaleType.FIT_CENTER);tape.setPadding(dp(4),dp(6),dp(4),dp(6));tape.setContentDescription("마스킹 테이프 "+i);
            try(InputStream in=getAssets().open("stickers/"+name)){tape.setImageBitmap(BitmapFactory.decodeStream(in));}catch(IOException ignored){}
            tape.setOnClickListener(v->{if(holder[0]!=null)holder[0].dismiss();placementText="";placementRot=-4f;placeElement("image",name);});
            tapeRow.addView(tape,new LinearLayout.LayoutParams(0,-1,1));
        }
        TextView mine=pill("내 이미지로 스티커 만들기","이미지 선택",ACTIVE_BG,ACTIVE_FG,v->{if(holder[0]!=null)holder[0].dismiss();pickImage();});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(44));mp.topMargin=dp(10);box.addView(mine,mp);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        holder[0]=new AlertDialog.Builder(this).setTitle("스티커").setView(scroll).setNegativeButton("닫기",null).create();holder[0].show();
    }
    private void importVideo(Uri source){
        final DocumentSession session=activeSession;toast("동영상을 가져오는 중…");
        new Thread(()->{try{
            File folder=new File(getFilesDir(),"videos");folder.mkdirs();String name=UUID.randomUUID()+".mp4";File target=new File(folder,name);long copied=0;
            // cloud providers (Google Drive...) hand out a stream that may need the network and may be big: copy with a progress toast, allow up to 2GB, and check free space first
            long expected=-1;try(android.database.Cursor c=getContentResolver().query(source,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))expected=c.getLong(0);}catch(Exception ignored){}
            if(expected>2048L*1024*1024)throw new IOException("2GB 이하의 동영상만 넣을 수 있습니다");
            if(expected>0&&folder.getUsableSpace()<expected+(64L<<20))throw new IOException("기기 저장 공간이 부족합니다");
            long lastToast=0;
            try(InputStream in=getContentResolver().openInputStream(source)){
                if(in==null)throw new IOException("파일을 열 수 없습니다 (드라이브 앱에서 '오프라인 사용'을 켜거나 먼저 내려받아 주세요)");
                try(OutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[1<<16];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);copied+=n;if(copied>2048L*1024*1024)throw new IOException("2GB 이하의 동영상만 넣을 수 있습니다");
                    long now=System.currentTimeMillis();if(expected>0&&now-lastToast>1500){lastToast=now;final int percent=(int)(copied*100/expected);runOnUiThread(()->toast("동영상을 가져오는 중… "+percent+"%"));}}}
            }catch(IOException|SecurityException error){target.delete();throw new IOException(error.getMessage()==null?"파일을 읽는 중 오류 ("+error.getClass().getSimpleName()+")":error.getMessage());}
            if(copied<=0){target.delete();throw new IOException("빈 파일입니다. 드라이브 파일이 아직 내려받아지지 않았을 수 있습니다");}
            Bitmap frame=null;android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
            try{retriever.setDataSource(target.getPath());
                long[] at={500000L,0L,2000000L};for(long t:at){if(frame!=null)break;try{frame=retriever.getFrameAtTime(t,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);}catch(RuntimeException ignored){}if(frame==null)try{frame=retriever.getFrameAtTime(t,android.media.MediaMetadataRetriever.OPTION_CLOSEST);}catch(RuntimeException ignored){}}
                if(frame==null)try{frame=retriever.getFrameAtTime();}catch(RuntimeException ignored){}
            }catch(RuntimeException ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
            // no preview frame (unusual codec): keep the video anyway with a plain dark card, it still plays
            if(frame==null){frame=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);frame.eraseColor(0xFF2C2C2E);}
            float shrink=Math.min(1f,900f/Math.max(frame.getWidth(),frame.getHeight()));if(shrink<1f){Bitmap small=Bitmap.createScaledBitmap(frame,Math.max(1,Math.round(frame.getWidth()*shrink)),Math.max(1,Math.round(frame.getHeight()*shrink)),true);frame.recycle();frame=small;}
            File images=new File(getFilesDir(),"images");images.mkdirs();String thumb=UUID.randomUUID()+".png";
            try(OutputStream out=new FileOutputStream(new File(images,thumb))){if(!frame.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("미리보기 저장 실패");}finally{frame.recycle();}
            runOnUiThread(()->{if(session!=null&&sessions.contains(session)){switchDocument(session);placementText=name;placeOrDrop("video",thumb);}});
        }catch(Exception error){runOnUiThread(()->toast("동영상 가져오기 실패: "+error.getMessage()));}},"video-import").start();
    }
    // ---- in-document playback: the player opens right on the element's rectangle; page changes, zoom and closing stop it
    private FrameLayout inlinePlayer;private Runnable inlineStopper;private View customWebView;private WebChromeClient.CustomViewCallback customCallback;
    private void stopInlinePlayer(){
        if(inlineStopper!=null){try{inlineStopper.run();}catch(RuntimeException ignored){}inlineStopper=null;}
        if(inlinePlayer!=null&&viewportLayer!=null)viewportLayer.removeView(inlinePlayer);inlinePlayer=null;
    }
    private Rect inlineBoxFor(AnnotationStore.PageElement element){
        PdfPageView view=splitMode&&pageView!=null?pageView:firstPageView!=null&&firstPageView.getPageNumber()==element.page?firstPageView:secondPageView;View papers=viewportLayer.getChildAt(0);if(view==null||papers==null)return null;
        RectF r=view.pageRect();if(r.isEmpty())return null;
        float l=papers.getLeft()+view.getLeft()+r.left+element.left*r.width(),t=papers.getTop()+view.getTop()+r.top+element.top*r.height(),rr=papers.getLeft()+view.getLeft()+r.left+element.right*r.width(),b=papers.getTop()+view.getTop()+r.top+element.bottom*r.height();
        int minW=dp(220),minH=dp(124),w=Math.max(minW,Math.round(rr-l)),h=Math.max(minH,Math.round(b-t));
        int cx=Math.round((l+rr)/2),cy=Math.round((t+b)/2),left=Math.max(0,Math.min(viewportLayer.getWidth()-w,cx-w/2)),top=Math.max(0,Math.min(viewportLayer.getHeight()-h,cy-h/2));
        return new Rect(left,top,left+Math.min(w,viewportLayer.getWidth()),top+Math.min(h,viewportLayer.getHeight()));
    }
    private FrameLayout openInlineFrame(AnnotationStore.PageElement element){
        stopInlinePlayer();Rect box=inlineBoxFor(element);if(box==null){toast("재생할 위치를 찾을 수 없습니다");return null;}
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);frame.setElevation(dp(8));frame.setTag("inline_player");
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(box.width(),box.height(),Gravity.TOP|Gravity.START);lp.leftMargin=box.left;lp.topMargin=box.top;
        viewportLayer.addView(frame,lp);inlinePlayer=frame;return frame;
    }
    private void addInlineClose(FrameLayout frame){
        ImageButton close=icon(R.drawable.ic_close,"재생 닫기",Color.WHITE,v->stopInlinePlayer());close.setBackground(round(0x99000000,16));close.setPadding(dp(6),dp(6),dp(6),dp(6));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.TOP|Gravity.END);cp.setMargins(0,dp(4),dp(4),0);frame.addView(close,cp);
    }
    private void playInline(AnnotationStore.PageElement element){
        if(element.kind.equals("youtube")){playYoutubeInline(element.text);return;}
        File file=new File(new File(getFilesDir(),"videos"),element.text);if(!file.isFile()){toast("동영상 파일을 찾을 수 없습니다");return;}
        FrameLayout frame=openInlineFrame(element);if(frame==null)return;
        VideoSurface video=new VideoSurface(this);video.setVideoURI(Uri.fromFile(file));
        frame.addView(video,VideoSurface.centred());
        addInlineClose(frame);
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.TOP|Gravity.START);fp.setMargins(dp(4),dp(4),0,0);
        ImageButton big=icon(R.drawable.ic_fullscreen,"크게 보기",Color.WHITE,v->{stopInlinePlayer();showVideoPlayer(element);});big.setBackground(round(0x99000000,16));big.setPadding(dp(6),dp(6),dp(6),dp(6));frame.addView(big,fp);
        final Runnable[] stopBar=new Runnable[1];
        video.setOnPreparedListener(()->{video.start();stopBar[0]=addVideoBar(frame,video);});video.setOnErrorListener(()->{stopInlinePlayer();videoUnsupported(file);});
        inlineStopper=()->{if(stopBar[0]!=null)stopBar[0].run();video.stopPlayback();};
    }
    private TextView barButton(String label,View.OnClickListener click){TextView b=new TextView(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setGravity(Gravity.CENTER);b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setOnClickListener(click);return b;}
    /** Always-visible play / pause / ±10 s bar with a seek slider at the bottom of a video frame. */
    private Runnable addVideoBar(FrameLayout frame,VideoSurface video){
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackgroundColor(0xB0000000);bar.setPadding(dp(4),dp(2),dp(4),dp(2));
        TextView play=barButton("⏸",null);
        play.setOnClickListener(v->{if(video.isPlaying()){video.pause();play.setText("▶");}else{video.start();play.setText("⏸");}});
        TextView time=new TextView(this);time.setTextColor(Color.WHITE);time.setTextSize(11);
        android.widget.SeekBar seek=new android.widget.SeekBar(this);seek.setMax(Math.max(1,video.getDuration()));
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar b,int p,boolean user){if(user)video.seekTo(p);}
            public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){}});
        bar.addView(barButton("⏪10",v->video.seekTo(Math.max(0,video.getCurrentPosition()-10000))));bar.addView(play);bar.addView(barButton("10⏩",v->video.seekTo(Math.min(video.getDuration(),video.getCurrentPosition()+10000))));
        bar.addView(seek,new LinearLayout.LayoutParams(0,-2,1f));bar.addView(time);
        frame.addView(bar,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        final boolean[] live={true};final Handler h=new Handler(Looper.getMainLooper());
        Runnable tick=new Runnable(){public void run(){if(!live[0])return;int p=video.getCurrentPosition(),d=video.getDuration();seek.setMax(Math.max(1,d));seek.setProgress(p);play.setText(video.isPlaying()?"⏸":"▶");time.setText(String.format(java.util.Locale.US,"%d:%02d / %d:%02d",p/60000,p/1000%60,d/60000,d/1000%60));h.postDelayed(this,500);}};
        h.post(tick);
        return ()->{live[0]=false;};
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void playYoutubeInline(String id){
        AnnotationStore.PageElement target=null;for(AnnotationStore.PageElement e:store.elements)if(e.kind.equals("youtube")&&id.equals(e.text)&&e.page==currentPageForInline()){target=e;break;}
        if(target==null){for(AnnotationStore.PageElement e:store.elements)if(e.kind.equals("youtube")&&id.equals(e.text)){target=e;break;}}
        if(target==null)return;FrameLayout frame=openInlineFrame(target);if(frame==null)return;
        WebView web=new WebView(this);WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setMediaPlaybackRequiresUserGesture(false);web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r){return false;}});
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onShowCustomView(View view,CustomViewCallback callback){customWebView=view;customCallback=callback;addContentView(view,new FrameLayout.LayoutParams(-1,-1));}
            @Override public void onHideCustomView(){if(customWebView!=null){((ViewGroup)customWebView.getParent()).removeView(customWebView);customWebView=null;}if(customCallback!=null){customCallback.onCustomViewHidden();customCallback=null;}}
        });
        frame.addView(web,new FrameLayout.LayoutParams(-1,-1));
        addInlineClose(frame);
        String html="<html><body style='margin:0;background:#000'><div id='p' style='position:absolute;inset:0 0 40px 0'></div><script src='https://www.youtube.com/iframe_api'></script><script>var pl;function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{width:'100%',height:'100%',videoId:'"+id+"',playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,controls:1},events:{onReady:function(e){e.target.playVideo();}}});}function tg(){if(!pl)return;pl.getPlayerState()==1?pl.pauseVideo():pl.playVideo();}function sk(d){if(pl)pl.seekTo(Math.max(0,pl.getCurrentTime()+d),true);}</script></body></html>";
        web.loadDataWithBaseURL("https://www.youtube.com",html,"text/html","utf-8",null);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER);bar.setBackgroundColor(0xB0000000);
        bar.addView(barButton("⏪ 10초",v->web.evaluateJavascript("sk(-10)",null)));bar.addView(barButton("▶/⏸",v->web.evaluateJavascript("tg()",null)));bar.addView(barButton("10초 ⏩",v->web.evaluateJavascript("sk(10)",null)));
        frame.addView(bar,new FrameLayout.LayoutParams(-1,dp(40),Gravity.BOTTOM));
        inlineStopper=()->{if(customWebView!=null&&customCallback!=null){customCallback.onCustomViewHidden();}web.loadUrl("about:blank");web.stopLoading();web.destroy();};
    }
    private int currentPageForInline(){return pageView==null?0:pageView.getPageNumber();}
    private void showVideoPlayer(AnnotationStore.PageElement element){
        File file=new File(new File(getFilesDir(),"videos"),element.text);if(!file.isFile()){toast("동영상 파일을 찾을 수 없습니다");return;}
        final Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        VideoSurface video=new VideoSurface(this);video.setVideoURI(Uri.fromFile(file));frame.addView(video,VideoSurface.centred());final Runnable[] stopBar=new Runnable[1];
        ImageButton close=icon(R.drawable.ic_close,"동영상 닫기",Color.WHITE,v->dialog.dismiss());FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.END);cp.setMargins(0,dp(24),dp(8),0);frame.addView(close,cp);
        dialog.setContentView(frame);dialog.setOnDismissListener(d->{if(stopBar[0]!=null)stopBar[0].run();video.stopPlayback();});video.setOnPreparedListener(()->{video.start();stopBar[0]=addVideoBar(frame,video);});video.setOnErrorListener(()->{dialog.dismiss();videoUnsupported(file);});dialog.show();
    }
    /** The built-in player (MediaPlayer) cannot decode every container/codec (AVI with DivX/Xvid, WMV/ASF, FLV ...): hand the file to an installed video app instead of just failing. */
    private void videoUnsupported(File file){
        String mime=ShareProvider.videoMime(file);String kind=mime.contains("msvideo")?"AVI":mime.contains("wmv")?"WMV":mime.contains("matroska")?"MKV":mime.contains("flv")?"FLV":mime.contains("quicktime")?"MOV":mime.contains("mpeg")&&!mime.contains("mp4")?"MPEG":"이 형식";
        new AlertDialog.Builder(this).setTitle("앱 안에서 재생할 수 없는 동영상").setMessage("이 기기의 내장 재생기는 "+kind+" 동영상의 코덱을 지원하지 않습니다. 설치된 동영상 앱(VLC, MX Player 등)으로 바로 재생할 수 있습니다.").setPositiveButton("다른 앱으로 재생",(d,w)->openVideoExternally(file)).setNegativeButton("닫기",null).show();
    }
    private void openVideoExternally(File file){
        if(!file.isFile()){toast("동영상 파일을 찾을 수 없습니다");return;}
        try{Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(ShareProvider.videoUri(this,file),ShareProvider.videoMime(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(view,"동영상 재생 앱 선택").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
        catch(ActivityNotFoundException error){toast("이 동영상을 재생할 앱이 없습니다. VLC 같은 동영상 앱을 설치해 보세요");}
    }
    // ---- insert menu, shapes, tables, drag and drop
    private List<AnchoredMenu.Row> insertRows(){
        return AnchoredMenu.rows(
            new AnchoredMenu.Row("사진·이미지",R.drawable.ic_image,this::insertImage).tint(0xFF007AFF),
            new AnchoredMenu.Row("스티커",R.drawable.ic_sticker,this::showStickerPicker).tint(0xFFFF9500),
            new AnchoredMenu.Row("도형",R.drawable.ic_rect,()->showShapeDialog(null)).tint(0xFFAF52DE),
            new AnchoredMenu.Row("표",R.drawable.ic_table,()->showTableDialog(null)).tint(0xFF30B0C7),
            new AnchoredMenu.Row("동영상",R.drawable.ic_video,this::pickVideo).tint(0xFFFF3B30),
            new AnchoredMenu.Row("유튜브 링크",R.drawable.ic_youtube,this::askYoutube).tint(0xFFFF0000));
    }
    private void showInsertMenu(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        dropTarget=null;
        List<AnchoredMenu.Row> rows=new ArrayList<>(insertRows());
        rows.add(0,new AnchoredMenu.Row("메모",R.drawable.ic_note_add,this::toggleMemoMode).tint(0xFFFF9500).selected(memoMode));
        rows.add(1,new AnchoredMenu.Row("하이라이트",R.drawable.ic_highlight,()->{toggleHighlight();if(highlightMode)showMarkHighlightMenu(anchor);}).tint(0xFFF5C400).selected(highlightMode));
        AnchoredMenu.show(this,anchor,true,rows,null);
    }
    private View tapAnchor;
    /** Long press on empty paper: the same insert menu, and whatever is chosen is placed right there. */
    void showInsertMenuAt(PdfPageView view,int page,float x,float y,float viewX,float viewY){
        if(renderer==null||view==null||viewportLayer==null)return;
        dropTarget=new float[]{page,x,y};dropTime=System.currentTimeMillis();
        showMenuAt(view,viewX,viewY,insertRows(),null);
    }
    /** Shows a floating menu card next to a point inside a page view (above the point when it is in the lower half). */
    private void showMenuAt(PdfPageView view,float viewX,float viewY,List<AnchoredMenu.Row> rows,Runnable onDismiss){showMenuAt(view,viewX,viewY,rows,onDismiss,null);}
    private void showMenuAt(PdfPageView view,float viewX,float viewY,List<AnchoredMenu.Row> rows,Runnable onDismiss,android.graphics.Rect avoid){
        if(view==null||viewportLayer==null)return;
        View papers=viewportLayer.getChildAt(0);
        if(tapAnchor==null){tapAnchor=new View(this);viewportLayer.addView(tapAnchor,new FrameLayout.LayoutParams(dp(2),dp(2),Gravity.TOP|Gravity.START));}
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)tapAnchor.getLayoutParams();lp.leftMargin=Math.round(viewX+view.getLeft()+papers.getLeft());lp.topMargin=Math.round(viewY+view.getTop()+papers.getTop());tapAnchor.setLayoutParams(lp);
        final boolean above=viewY>view.getHeight()*.5f;
        tapAnchor.post(()->{PopupWindow w=AnchoredMenu.show(this,tapAnchor,above,rows,null,avoid);if(onDismiss!=null){selectionPopup=w;w.setOnDismissListener(()->{if(selectionPopup==w)selectionPopup=null;onDismiss.run();});}});
    }
    private LinearLayout colorRow(int[] colors,int[] chosen){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);final View[] swatches=new View[colors.length+1];final Runnable[] refresh=new Runnable[1];
        refresh[0]=()->{boolean custom=true;for(int i=0;i<colors.length;i++){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);boolean none=(colors[i]>>>24)==0;d.setColor(none?Color.WHITE:colors[i]);boolean on=chosen[0]==colors[i];if(on)custom=false;d.setStroke(dp(on?3:1),on?ACCENT:0xFFC7C7CC);swatches[i].setBackground(d);}
            GradientDrawable m=new GradientDrawable();m.setShape(GradientDrawable.OVAL);
            if(custom){m.setColor(chosen[0]);m.setStroke(dp(3),ACCENT);}else{m.setGradientType(GradientDrawable.SWEEP_GRADIENT);m.setColors(new int[]{0xFFFF3B30,0xFFFFCC00,0xFF34C759,0xFF00C7BE,0xFF007AFF,0xFFAF52DE,0xFFFF3B30});m.setStroke(dp(1),0xFFC7C7CC);}
            swatches[colors.length].setBackground(m);};
        // each swatch is a true circle: a square dot (side = the smaller of its cell's width and 30dp) centred in an equal-width cell
        for(int i=0;i<colors.length;i++){final int color=colors[i];View v=squareDot();v.setContentDescription((color>>>24)==0?"없음":"색상");swatches[i]=v;v.setOnClickListener(x->{chosen[0]=color;refresh[0].run();});row.addView(dotCell(v),new LinearLayout.LayoutParams(0,dp(34),1));}
        View more=squareDot();more.setContentDescription("다른 색·투명도 선택");swatches[colors.length]=more;more.setOnClickListener(x->ColorPicker.show(this,"색·투명도",chosen[0]==0?0x80007AFF:chosen[0],true,c->{chosen[0]=c;refresh[0].run();}));
        row.addView(dotCell(more),new LinearLayout.LayoutParams(0,dp(34),1));
        refresh[0].run();return row;
    }
    private View squareDot(){return new View(this){@Override protected void onMeasure(int w,int h){int side=Math.min(Math.min(View.MeasureSpec.getSize(w),View.MeasureSpec.getSize(h)),dp(30));setMeasuredDimension(side,side);}};}
    private FrameLayout dotCell(View dot){FrameLayout cell=new FrameLayout(this);cell.addView(dot,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));return cell;}
    private TextView sectionLabel(String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(12);t.setTextColor(0xFF8E8E93);t.setPadding(dp(4),dp(10),0,dp(2));return t;}
    private void showShapeDialog(AnnotationStore.PageElement existing){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        final int[] strokeColors={0xFF1C1C1E,0xFFFF3B30,0xFFFF9500,0xFFF5C400,0xFF34C759,0xFF30B0C7,0xFF007AFF,0xFFAF52DE};
        final int[] fillColors={0x00000000,0x66FF3B30,0x66FF9500,0x66F5C400,0x6634C759,0x6630B0C7,0x66007AFF,0x66AF52DE};
        String[] cur=existing!=null&&Shapes.validShape(existing.text)?existing.text.split("\\|"):new String[]{"rect","FF007AFF","00000000","3"};
        final int[] stroke={Shapes.parseColor(cur[1])},fill={Shapes.parseColor(cur[2])},width={Integer.parseInt(cur[3])};final String[] kind={cur[0]};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(4),dp(14),dp(4));
        if(existing==null){
            final TextView[] buttons=new TextView[Shapes.KINDS.length];final Runnable[] refresh=new Runnable[1];
            refresh[0]=()->{for(int i=0;i<buttons.length;i++){boolean on=Shapes.KINDS[i].equals(kind[0]);buttons[i].setBackground(round(on?ACTIVE_BG:0xFFF2F2F7,10));buttons[i].setTextColor(on?ACTIVE_FG:NAVY);}};
            LinearLayout line=null;
            for(int i=0;i<Shapes.KINDS.length;i++){
                if(i%3==0){line=new LinearLayout(this);box.addView(line,new LinearLayout.LayoutParams(-1,dp(44)));}
                final String id=Shapes.KINDS[i];TextView b=new TextView(this);b.setText(Shapes.NAMES[i]);b.setTextSize(13);b.setGravity(Gravity.CENTER);b.setOnClickListener(v->{kind[0]=id;refresh[0].run();});buttons[i]=b;
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.setMargins(dp(3),dp(3),dp(3),dp(3));line.addView(b,lp);
            }
            refresh[0].run();
        }
        box.addView(sectionLabel("선 색"));box.addView(colorRow(strokeColors,stroke));
        box.addView(sectionLabel("채우기 색 (왼쪽 흰 원 = 없음)"));box.addView(colorRow(fillColors,fill));
        TextView widthLabel=sectionLabel("선 굵기 "+width[0]);box.addView(widthLabel);
        android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(11);bar.setProgress(width[0]-1);bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){width[0]=value+1;widthLabel.setText("선 굵기 "+width[0]);}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b){}});
        box.addView(bar);
        final float[] turn={existing==null?0f:existing.rot};TextView turnLabel=sectionLabel("회전 "+Math.round(turn[0])+"°");box.addView(turnLabel);
        android.widget.SeekBar turnBar=new android.widget.SeekBar(this);turnBar.setMax(72);turnBar.setProgress(Math.round(turn[0]/5f)%73);turnBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){turn[0]=value*5f;turnLabel.setText("회전 "+Math.round(turn[0])+"°");}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b){}});
        box.addView(turnBar);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle(existing==null?"도형":"도형 모양 수정").setView(scroll).setPositiveButton(existing==null?"넣기":"적용",(d,w)->{
            String spec=Shapes.shapeSpec(kind[0],stroke[0],fill[0],width[0]);
            if(existing==null){placementText=spec;placementRot=turn[0];placeElement("shape","");}
            else{existing.text=spec;existing.rot=turn[0];store.save();redrawPages();}
        }).setNegativeButton("취소",null).show();
    }
    private void showTableDialog(AnnotationStore.PageElement existing){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        final int[] lineColors={0xFF1C1C1E,0xFF8E8E93,0xFFFF3B30,0xFFFF9500,0xFF34C759,0xFF30B0C7,0xFF007AFF,0xFFAF52DE};
        final int[] headColors={0x00000000,0xFFE5F0FF,0xFFFFE3E8,0xFFFFF4CC,0xFFE3F7E8,0xFFEDE3FA,0xFFD9D9DE,0xFF1C1C1E};
        final int[] fillColors={0x00000000,0xFFFFFFFF,0xFFF2F2F7,0xFFFFF9E0,0xFFEAF6EC,0xFFEAF2FF,0xFFFCEAF0,0xFFF3ECFA};
        final Shapes.Table base=existing!=null?Shapes.Table.parse(existing.text):Shapes.Table.create(3,3,0xFF3A3A3C,0xFFE5F0FF,0x00FFFFFF);
        final int[] line={base.line},head={base.head},fill={base.fill};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(4),dp(14),dp(4));
        LinearLayout sizes=new LinearLayout(this);sizes.setGravity(Gravity.CENTER);
        EditText rowsInput=new EditText(this),colsInput=new EditText(this);rowsInput.setInputType(2);colsInput.setInputType(2);rowsInput.setText(String.valueOf(base.rows));colsInput.setText(String.valueOf(base.cols));rowsInput.setGravity(Gravity.CENTER);colsInput.setGravity(Gravity.CENTER);
        TextView a=new TextView(this);a.setText("행 ");TextView b=new TextView(this);b.setText("   열 ");sizes.addView(a);sizes.addView(rowsInput,new LinearLayout.LayoutParams(dp(64),-2));sizes.addView(b);sizes.addView(colsInput,new LinearLayout.LayoutParams(dp(64),-2));
        box.addView(sizes);
        box.addView(sectionLabel("선 색"));box.addView(colorRow(lineColors,line));
        box.addView(sectionLabel("머리글 칸 색 (첫 줄)"));box.addView(colorRow(headColors,head));
        box.addView(sectionLabel("바탕 색"));box.addView(colorRow(fillColors,fill));
        final Shapes.Table[] painted={base};
        TextView paintCells=new TextView(this);paintCells.setText("셀별 색 지정 …");paintCells.setTextColor(ACCENT);paintCells.setTextSize(15);paintCells.setTypeface(Typeface.DEFAULT_BOLD);paintCells.setPadding(dp(2),dp(14),0,dp(8));
        paintCells.setOnClickListener(v->{int r=3,c=3;try{r=Integer.parseInt(rowsInput.getText().toString().trim());c=Integer.parseInt(colsInput.getText().toString().trim());}catch(NumberFormatException ignored){}r=Math.max(1,Math.min(30,r));c=Math.max(1,Math.min(12,c));painted[0]=painted[0].resized(r,c);showCellColorEditor(painted[0],null);});
        box.addView(paintCells);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle(existing==null?"표 만들기":"표 모양 수정").setWidth(400).setView(scroll).setPositiveButton(existing==null?"넣기":"적용",(d,w)->{
            int r=3,c=3;try{r=Integer.parseInt(rowsInput.getText().toString().trim());c=Integer.parseInt(colsInput.getText().toString().trim());}catch(NumberFormatException ignored){}
            r=Math.max(1,Math.min(30,r));c=Math.max(1,Math.min(12,c));
            Shapes.Table t=painted[0].resized(r,c);t.line=line[0];t.head=head[0];t.fill=fill[0];
            if(existing==null){placementText=t.serialize();placeElement("table","");}
            else{existing.text=t.serialize();store.save();redrawPages();}
        }).setNegativeButton("취소",null).show();
    }
    /** Cell-colour painter: pick a colour (the first swatch clears) and tap cells to paint them. Works on [t].bg in place; [resize] gives the current row/column count. */
    private void showCellColorEditor(Shapes.Table t,Runnable done){
        final int[] palette={0x00000000,0xFFFFF4CC,0xFFFFE3E8,0xFFE3F7E8,0xFFE5F0FF,0xFFEDE3FA,0xFFD9D9DE,0xFFFFB3B3,0xFFFFD6A5,0xFFB9E6C4,0xFF9EC9FF,0xFF1C1C1E};
        final int[] chosen={0xFFFFF4CC};
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(12),dp(4),dp(12),dp(4));
        box.addView(sectionLabel("칠할 색을 고른 뒤 칸을 누르세요 (맨 앞 흰 동그라미 = 색 지우기)"));box.addView(colorRow(palette,chosen));
        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);grid.setPadding(0,dp(10),0,dp(4));
        final View[] cellViews=new View[t.rows*t.cols];
        int side=Math.max(dp(30),Math.min(dp(56),dp(280)/t.cols));
        for(int r=0;r<t.rows;r++){
            LinearLayout line=new LinearLayout(this);grid.addView(line,new LinearLayout.LayoutParams(-2,-2));
            for(int c=0;c<t.cols;c++){
                final int index=r*t.cols+c;TextView cell=new TextView(this);cell.setGravity(Gravity.CENTER);cell.setTextSize(10);cell.setMaxLines(1);cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
                String label=t.cells[index]==null?"":t.cells[index].trim();cell.setText(label);cellViews[index]=cell;
                Runnable paintCell=()->{GradientDrawable g=new GradientDrawable();int color=t.bg[index];g.setColor((color>>>24)==0?Color.WHITE:color);g.setStroke(dp(1),0xFF8E8E93);cell.setBackground(g);cell.setTextColor((color>>>24)!=0&&(0.299f*((color>>16)&255)+0.587f*((color>>8)&255)+0.114f*(color&255))<110f?Color.WHITE:0xFF1C1C1E);};
                paintCell.run();cell.setOnClickListener(v->{t.bg[index]=chosen[0];paintCell.run();});
                line.addView(cell,new LinearLayout.LayoutParams(side,side));
            }
        }
        android.widget.HorizontalScrollView h=new android.widget.HorizontalScrollView(this);h.addView(grid);box.addView(h);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("셀 색 지정").setView(scroll).setPositiveButton("확인",(d,w)->{if(done!=null)done.run();}).show();
    }
    private void editTableCells(AnnotationStore.PageElement element){
        final Shapes.Table t=Shapes.Table.parse(element.text);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(4),dp(10),dp(4));
        final EditText[] inputs=new EditText[t.cells.length];
        for(int r=0;r<t.rows;r++){
            LinearLayout line=new LinearLayout(this);box.addView(line,new LinearLayout.LayoutParams(-1,-2));
            for(int c=0;c<t.cols;c++){EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(13);input.setText(t.cells[r*t.cols+c]);input.setHint(r==0?"제목":"");inputs[r*t.cols+c]=input;line.addView(input,new LinearLayout.LayoutParams(Math.max(dp(84),dp(300)/t.cols),-2));}
        }
        android.widget.HorizontalScrollView h=new android.widget.HorizontalScrollView(this);h.addView(box);android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(h);
        new AlertDialog.Builder(this).setTitle("표 내용").setView(scroll).setPositiveButton("저장",(d,w)->{for(int i=0;i<inputs.length;i++)t.cells[i]=inputs[i].getText().toString();element.text=t.serialize();store.save();redrawPages();}).setNeutralButton("셀 색",(d,w)->{for(int i=0;i<inputs.length;i++)t.cells[i]=inputs[i].getText().toString();showCellColorEditor(t,()->{element.text=t.serialize();store.save();redrawPages();});}).setNegativeButton("취소",null).show();
    }
    private static String youtubeId(String text){
        if(text==null)return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^\\s]*&)?v=|shorts/|embed/|live/|v/)|i[0-9]?\\.ytimg\\.com/vi(?:_webp)?/)([A-Za-z0-9_-]{11})").matcher(text.trim());
        return m.find()?m.group(1):null;
    }
    private void askYoutube(){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        EditText input=new EditText(this);input.setSingleLine();input.setTextSize(15);input.setHint("https://youtu.be/… 또는 youtube.com/watch?v=…");input.setBackground(round(0xFFF2F2F7,12));input.setPadding(dp(14),dp(12),dp(14),dp(12));input.setSelectAllOnFocus(true);
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=clipboard.getPrimaryClip();if(data!=null&&data.getItemCount()>0&&data.getItemAt(0).getText()!=null&&youtubeId(data.getItemAt(0).getText().toString())!=null)input.setText(data.getItemAt(0).getText());
        new AlertDialog.Builder(this).setTitle("유튜브 링크").setMessage("동영상 주소를 붙여 넣으면 페이지에 넣을 수 있습니다").setView(input).setPositiveButton("넣기",(d,w)->{String id=youtubeId(input.getText().toString());if(id==null)toast("유튜브 주소를 입력하세요");else importYoutube(id);}).setNegativeButton("취소",null).show();
    }
    private void openYoutube(String id){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/watch?v="+id)));}catch(ActivityNotFoundException error){toast("유튜브를 열 앱이 없습니다");}}
    /** A YouTube video is stored as its video id plus a downloaded thumbnail; tapping it opens the video in YouTube. */
    private void importYoutube(String id){
        final DocumentSession session=activeSession;toast("유튜브 영상 정보를 가져오는 중…");
        new Thread(()->{
            String thumb="";
            try{
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("https://img.youtube.com/vi/"+id+"/mqdefault.jpg").openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(12000);
                Bitmap image;try(InputStream in=connection.getInputStream()){image=BitmapFactory.decodeStream(in);}
                if(image!=null){File folder=new File(getFilesDir(),"images");folder.mkdirs();String name=UUID.randomUUID()+".png";try(OutputStream out=new FileOutputStream(new File(folder,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);thumb=name;}finally{image.recycle();}}
            }catch(Exception ignored){}
            final String asset=thumb;
            runOnUiThread(()->{if(session!=null&&sessions.contains(session)){switchDocument(session);placementText=id;placeOrDrop("youtube",asset);}});
        },"youtube-import").start();
    }
    private float[] dropTarget;private long dropTime;
    private void placeOrDrop(String kind,String asset){placeElement(kind,asset);}
    private void installDrop(View target){
        target.setOnDragListener((v,e)->{
            switch(e.getAction()){
                case android.view.DragEvent.ACTION_DRAG_STARTED:{android.content.ClipDescription cd=e.getClipDescription();return renderer!=null&&cd!=null&&(cd.hasMimeType("image/*")||cd.hasMimeType("video/*")||cd.hasMimeType("text/uri-list")||cd.hasMimeType("text/plain")||cd.hasMimeType("text/html"));}
                case android.view.DragEvent.ACTION_DROP:return handleDrop(e);
                default:return true;
            }
        });
    }
    private boolean handleDrop(android.view.DragEvent e){
        if(renderer==null)return false;ClipData clip=e.getClipData();if(clip==null)return false;
        Uri uri=null;String dropYt=null;
        for(int i=0;i<clip.getItemCount()&&dropYt==null;i++){ClipData.Item item=clip.getItemAt(i);dropYt=youtubeId(String.valueOf(item.getUri()));if(dropYt==null)dropYt=youtubeId(item.getText()==null?null:item.getText().toString());if(dropYt==null)dropYt=youtubeId(item.getHtmlText());}
        for(int i=0;i<clip.getItemCount()&&uri==null&&dropYt==null;i++){
            ClipData.Item item=clip.getItemAt(i);
            if(item.getUri()!=null)uri=item.getUri();
            else{
                String html=item.getHtmlText();CharSequence text=item.getText();
                if(html!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("src=[\"']([^\"']+)[\"']").matcher(html);if(m.find())uri=Uri.parse(m.group(1));}
                if(uri==null&&text!=null&&text.toString().trim().matches("https?://\\S+"))uri=Uri.parse(text.toString().trim());
            }
        }
        if(uri==null&&dropYt==null){toast("끌어 놓은 항목에서 이미지나 동영상을 찾지 못했습니다");return false;}
        try{requestDragAndDropPermissions(e);}catch(RuntimeException ignored){}
        View papers=viewportLayer.getChildAt(0);float px=e.getX()-papers.getLeft(),py=e.getY()-papers.getTop();
        PdfPageView hit=firstPageView;if(splitMode){PdfPageView under=paneAt(px,py);if(under!=null)hit=under;}else if(twoPage&&secondPageView.getVisibility()==View.VISIBLE&&px>=secondPageView.getLeft())hit=secondPageView;
        float[] n=hit.toPage(px-hit.getLeft(),py-hit.getTop());dropTarget=new float[]{hit.getPageNumber(),n[0],n[1]};dropTime=System.currentTimeMillis();
        if(dropYt!=null){importYoutube(dropYt);return true;}
        String scheme=uri.getScheme()==null?"":uri.getScheme();final Uri source=uri;
        if(scheme.equals("http")||scheme.equals("https")){downloadDroppedImage(uri.toString());return true;}
        boolean video=clip.getDescription().hasMimeType("video/*");
        if(!video){String type=getContentResolver().getType(uri);video=type!=null&&type.startsWith("video/");}
        if(video)importVideo(source);else importImage(source);
        return true;
    }
    private void downloadDroppedImage(String address){
        toast("이미지를 가져오는 중…");
        new Thread(()->{try{
            java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(address).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","Mozilla/5.0");
            String type=connection.getContentType();if(type!=null&&type.startsWith("video/"))throw new IOException("동영상은 파일로 저장한 뒤 끌어 놓으세요");
            File temp=new File(getCacheDir(),"drop-"+System.nanoTime());long copied=0;
            try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[1<<15];int n;while((n=in.read(buffer))>0){out.write(buffer,0,n);copied+=n;if(copied>40L*1024*1024)throw new IOException("이미지가 너무 큽니다");}}
            runOnUiThread(()->importImage(Uri.fromFile(temp)));
        }catch(Exception error){runOnUiThread(()->{dropTarget=null;toast("이미지를 가져오지 못했습니다: "+error.getMessage());});}},"drop-download").start();
    }
    @Override public boolean onKeyDown(int keyCode,android.view.KeyEvent event){
        if(keyCode==android.view.KeyEvent.KEYCODE_V&&event.isCtrlPressed()&&renderer!=null&&!(getCurrentFocus() instanceof EditText)){pasteImage();return true;}
        return super.onKeyDown(keyCode,event);
    }
    // ---- hyperlinks
    private void startHyperlink(){if(renderer==null){toast("문서를 먼저 여세요");return;}startTextSelection();toast("링크를 걸 글자를 드래그해 선택한 뒤 ‘링크’를 누르세요");}
    /** Accepts a web address or a page number and returns the stored target, or null when it is not valid. */
    private String linkTarget(String input){
        String text=input==null?"":input.trim();if(text.isEmpty())return null;
        if(text.matches("\\d{1,6}")){int page=Integer.parseInt(text);return renderer!=null&&page>=1&&page<=renderer.getPageCount()?"page:"+(page-1):null;}
        if(!text.contains("://")&&text.contains(".")&&!text.contains(" "))text="https://"+text;
        return validWebUrl(text)&&!text.contains(" ")?text:null;
    }
    private void createHyperlink(PdfPageView.TextSelection selection){
        if(store==null)return;final AnnotationStore target=store;final int page=currentPage;
        chooseLinkTarget(link->{
            int group=new Random().nextInt(Integer.MAX_VALUE)+1;List<RectF> pieces=selection.bounds==null||selection.bounds.isEmpty()?Collections.singletonList(selection.unionBounds):selection.bounds;
            for(RectF b:pieces){AnnotationStore.PageElement e=new AnnotationStore.PageElement();e.page=page;e.kind="hyperlink";e.text=link;e.color=group;e.left=Math.max(0f,b.left);e.top=Math.max(0f,b.top);e.right=Math.min(1f,b.right);e.bottom=Math.min(1f,b.bottom);if(e.right-e.left<.005f||e.bottom-e.top<.003f)continue;target.elements.add(e);}
            target.save();for(PdfPageView v:paneViews)if(v!=null)v.stopTextSelection();syncOtherTools();redrawPages();if(sidebarVisible&&panelTab==4)rebuildInsertions();toast("링크를 만들었습니다. 글자를 탭하면 바로 열리고, 길게 누르면 수정·삭제 메뉴가 나옵니다");
        });
    }
    /** Web address, a page of this document, or another document of the library (optionally at a page). */
    private void chooseLinkTarget(java.util.function.Consumer<String> done){
        String[] kinds={"웹 주소","이 문서의 페이지","다른 문서"};
        new AlertDialog.Builder(this).setTitle("연결 대상").setItems(kinds,(d,index)->{
            if(index==0)askLinkText("웹 주소","https://…",false,done);
            else if(index==1)askLinkText("이 문서의 페이지 (1~"+(renderer==null?1:renderer.getPageCount())+")","페이지 번호",true,done);
            else pickLinkDocument(done);
        }).setNegativeButton("취소",null).show();
    }
    private void askLinkText(String title,String hint,boolean number,java.util.function.Consumer<String> done){
        EditText input=new EditText(this);input.setHint(hint);input.setSingleLine();if(number)input.setInputType(2);input.setPadding(dp(24),dp(12),dp(24),dp(12));
        new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("확인",(d,w)->{String link=linkTarget(input.getText().toString());if(link==null)toast(number?"올바른 페이지 번호를 입력하세요":"http(s) 주소를 입력하세요");else done.accept(link);}).setNegativeButton("취소",null).show();
    }
    private void pickLinkDocument(java.util.function.Consumer<String> done){
        final List<File> docs=library.allDocuments("");if(docs.isEmpty()){toast("문서함에 연결할 문서가 없습니다");return;}
        final List<File> shown=docs.size()>300?docs.subList(0,300):docs;String[] names=new String[shown.size()];for(int i=0;i<names.length;i++)names[i]=shown.get(i).getName().replaceFirst("(?i)\\.pdf$","");
        new AlertDialog.Builder(this).setTitle("연결할 문서").setItems(names,(d,index)->{
            File file=shown.get(index);String relative;try{relative=library.root.toPath().relativize(file.toPath()).toString();}catch(RuntimeException error){toast("문서를 연결할 수 없습니다");return;}
            EditText input=new EditText(this);input.setHint("페이지 번호 (비우면 처음 페이지)");input.setSingleLine();input.setInputType(2);input.setPadding(dp(24),dp(12),dp(24),dp(12));
            new AlertDialog.Builder(this).setTitle(names[index]).setView(input).setPositiveButton("연결",(dd,w)->{int page=1;try{page=Math.max(1,Integer.parseInt(input.getText().toString().trim()));}catch(NumberFormatException ignored){}done.accept("doc:"+Uri.encode(relative)+"#"+(page-1));}).setNegativeButton("취소",null).show();
        }).setNegativeButton("취소",null).show();
    }
    private String describeLink(String link){
        if(link.startsWith("page:")){try{return "이 문서 "+(Integer.parseInt(link.substring(5))+1)+"쪽";}catch(NumberFormatException ignored){return link;}}
        if(link.startsWith("doc:")){String body=link.substring(4);int hash=body.lastIndexOf('#');String path=Uri.decode(hash>=0?body.substring(0,hash):body);String name=new File(path).getName().replaceFirst("(?i)\\.pdf$","");int page=1;if(hash>=0)try{page=Integer.parseInt(body.substring(hash+1))+1;}catch(NumberFormatException ignored){}return name+" · "+page+"쪽";}
        return link;
    }
    private void openHyperlink(AnnotationStore.PageElement element){
        if(element.text.startsWith("page:")){try{showPage(Integer.parseInt(element.text.substring(5)));}catch(NumberFormatException ignored){}return;}
        if(element.text.startsWith("doc:")){
            String body=element.text.substring(4);int hash=body.lastIndexOf('#');int page=0;if(hash>=0){try{page=Integer.parseInt(body.substring(hash+1));}catch(NumberFormatException ignored){}body=body.substring(0,hash);}
            File file=new File(library.root,Uri.decode(body));if(!library.managed(file)||!file.isFile()){toast("연결된 문서를 찾을 수 없습니다");return;}
            openPdf(Uri.fromFile(file),false,page,true);return;
        }
        if(validWebUrl(element.text))try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(element.text)));}catch(ActivityNotFoundException error){toast("링크를 열 앱이 없습니다");}
    }
    /** A tap follows the link at once; a long press asks what to do with it (the list in the side panel offers the same). */
    @Override public void onHyperlinkTapped(AnnotationStore.PageElement element,boolean longPress){if(longPress)showHyperlinkMenu(element);else openHyperlink(element);}
    private void showHyperlinkMenu(AnnotationStore.PageElement element){
        String title=describeLink(element.text);String[] labels={"열기","링크 수정","링크 삭제"};
        new AlertDialog.Builder(this).setTitle(title.length()>60?title.substring(0,60)+"…":title).setItems(labels,(d,index)->{
            if(index==0)openHyperlink(element);
            else if(index==1)editHyperlink(element);
            else{deleteElement(element);toast("링크를 삭제했습니다");}
        }).show();
    }
    private void editHyperlink(AnnotationStore.PageElement element){
        chooseLinkTarget(link->{String old=element.text;for(AnnotationStore.PageElement e:store.elements)if(e.kind.equals("hyperlink")&&e.page==element.page&&e.color==element.color&&e.text.equals(old))e.text=link;store.save();redrawPages();if(sidebarVisible&&panelTab==4)rebuildInsertions();});
    }
    // ---- side-panel list of everything inserted in the document: links, pictures, videos, recordings, shapes, tables, typed text
    private static String insertionLabel(AnnotationStore.PageElement e){
        switch(e.kind){case "hyperlink":return "링크";case "link":return "웹 링크";case "audio":return "녹음";case "image":return "사진";case "video":return "동영상";case "youtube":return "유튜브";case "sticker":return "스티커";case "shape":return "도형";case "table":return "표";default:return "타이핑";}
    }
    private void rebuildInsertions(){
        if(insertList==null)return;insertList.removeAllViews();if(store==null)return;
        List<AnnotationStore.PageElement> items=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(AnnotationStore.PageElement e:store.elements){if(e.kind.equals("audio"))continue;if(e.kind.equals("hyperlink")&&!seen.add(e.page+"|"+e.color+"|"+e.text))continue;items.add(e);}
        items.sort(Comparator.comparingInt((AnnotationStore.PageElement e)->e.page).thenComparingDouble(e->e.top));
        TextView heading=new TextView(this);heading.setTag("insert_heading");heading.setText("삽입한 항목 "+items.size()+"개");heading.setTextSize(12);heading.setTextColor(0xFF8E8E93);heading.setPadding(dp(6),dp(4),0,dp(6));insertList.addView(heading);
        if(items.isEmpty()){TextView empty=new TextView(this);empty.setText("링크·사진·동영상·도형·표·타이핑·메모·하이라이트를 넣으면 여기에 모여 보입니다.\n항목을 누르면 그 위치로 이동하고, ⋮ 로 열기·수정·삭제를 합니다.");empty.setTextSize(12);empty.setTextColor(0xFF8E8E93);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(4),dp(18),dp(4),dp(8));insertList.addView(empty);}
        for(AnnotationStore.PageElement item:items){
            LinearLayout row=new LinearLayout(this);row.setTag("insert_item");row.setPadding(dp(12),dp(8),dp(2),dp(8));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(round(Color.WHITE,14));
            String detail=item.kind.equals("hyperlink")?describeLink(item.text):item.kind.equals("link")?item.text:item.kind.equals("audio")?item.text:item.kind.equals("youtube")||item.kind.equals("video")||item.kind.equals("image")||item.kind.equals("sticker")?"":item.text==null?"":item.text.trim();
            if(detail.length()>40)detail=detail.substring(0,40)+"…";
            TextView title=new TextView(this);title.setText(insertionLabel(item)+" · p"+(item.page+1)+(detail.isEmpty()?"":"\n"+detail));title.setTextSize(12.5f);title.setTextColor(NAVY);title.setMaxLines(3);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            row.setOnClickListener(v->{showPage(item.page);pageView.post(()->pageView.focusOnPoint((item.left+item.right)/2,(item.top+item.bottom)/2));});
            row.addView(icon(R.drawable.ic_more_vert,"삽입 항목 관리",NAVY,v->showInsertionMenu(item,v)),new LinearLayout.LayoutParams(dp(40),dp(40)));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);insertList.addView(row,params);
        }
        appendMarkList();
    }
    private void showInsertionMenu(AnnotationStore.PageElement item,View anchor){
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        rows.add(new AnchoredMenu.Row("이동",R.drawable.ic_page,()->{showPage(item.page);pageView.post(()->pageView.focusOnPoint((item.left+item.right)/2,(item.top+item.bottom)/2));}).tint(0xFF30B0C7));
        if(item.kind.equals("hyperlink")||item.kind.equals("link"))rows.add(new AnchoredMenu.Row("열기",R.drawable.ic_link,()->{if(item.kind.equals("hyperlink"))openHyperlink(item);else if(validWebUrl(item.text))try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(item.text)));}catch(ActivityNotFoundException error){toast("링크를 열 앱이 없습니다");}}).tint(0xFF5856D6));
        if(item.kind.equals("video")||item.kind.equals("youtube"))rows.add(new AnchoredMenu.Row("재생",R.drawable.ic_video,()->{showPage(item.page);pageView.post(()->playInline(item));}).tint(0xFFFF3B30));
        if(item.kind.equals("hyperlink"))rows.add(new AnchoredMenu.Row("수정",R.drawable.ic_compose,()->editHyperlink(item)).tint(0xFF007AFF));
        else if(item.kind.equals("link")||item.kind.equals("text"))rows.add(new AnchoredMenu.Row("수정",R.drawable.ic_compose,()->{showPage(item.page);editPageElement(item,false);}).tint(0xFF007AFF));
        if(!item.kind.equals("hyperlink"))rows.add(new AnchoredMenu.Row("위치·크기",R.drawable.ic_fullscreen,()->editElementGeometry(item)).tint(0xFF34C759));
        rows.add(new AnchoredMenu.Row("삭제",R.drawable.ic_delete,()->{if(item.kind.equals("audio"))deleteRecording(item);else deleteElement(item);if(sidebarVisible&&panelTab==4)rebuildInsertions();toast("삭제했습니다");}).danger());
        AnchoredMenu.showRightOf(this,anchor,sidePanel,rows);
    }
    private void editElementGeometry(AnnotationStore.PageElement element){LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);String[] labels={"왼쪽 (%)","위쪽 (%)","너비 (%)","높이 (%)"};float[] values={element.left*100,element.top*100,(element.right-element.left)*100,(element.bottom-element.top)*100};EditText[] inputs=new EditText[4];for(int i=0;i<4;i++){inputs[i]=new EditText(this);inputs[i].setHint(labels[i]);inputs[i].setInputType(8194);inputs[i].setText(String.format(Locale.US,"%.1f",values[i]));panel.addView(inputs[i]);}new AlertDialog.Builder(this).setTitle("위치·크기 · %").setView(panel).setPositiveButton("적용",(d,w)->{try{float x=Float.parseFloat(inputs[0].getText().toString())/100,y=Float.parseFloat(inputs[1].getText().toString())/100,width=Float.parseFloat(inputs[2].getText().toString())/100,height=Float.parseFloat(inputs[3].getText().toString())/100;if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(width)||!Float.isFinite(height)||x<0||y<0||width<=0||height<=0||x+width>1||y+height>1)throw new IllegalArgumentException();element.left=x;element.top=y;element.right=x+width;element.bottom=y+height;store.save();pageView.invalidate();}catch(Exception error){toast("페이지 안에 들어가는 위치와 크기를 입력하세요");}}).setNegativeButton("취소",null).show();}
    private void pasteImage(){if(renderer==null)return;ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=clipboard.getPrimaryClip();if(data!=null&&data.getItemCount()>0&&data.getItemAt(0).getUri()==null){CharSequence t=data.getItemAt(0).getText();String id=t==null?null:youtubeId(t.toString());if(id!=null){importYoutube(id);return;}}Uri image=data!=null&&data.getItemCount()>0?data.getItemAt(0).getUri():null;if(image!=null&&"content".equals(image.getScheme()))importImage(image);else new AlertDialog.Builder(this).setTitle("이미지 붙여넣기").setMessage("클립보드에 이미지가 없습니다. 브라우저의 ‘이미지 복사’를 사용하거나 저장된 이미지를 선택하세요.").setPositiveButton("이미지 선택",(d,w)->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),IMPORT_IMAGE)).setNegativeButton("닫기",null).show();}
    private void importImage(Uri source){final DocumentSession session=activeSession;new Thread(()->{try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream in=getContentResolver().openInputStream(source)){BitmapFactory.decodeStream(in,null,options);}if(options.outWidth<=0||options.outHeight<=0)throw new IOException("이미지를 읽을 수 없습니다");options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1600)options.inSampleSize*=2;Bitmap image;try(InputStream in=getContentResolver().openInputStream(source)){image=BitmapFactory.decodeStream(in,null,options);}if(image==null)throw new IOException("이미지 형식이 지원되지 않습니다");File folder=new File(getFilesDir(),"images");folder.mkdirs();String name=UUID.randomUUID()+".png";try(OutputStream out=new FileOutputStream(new File(folder,name))){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("이미지 저장 실패");}finally{image.recycle();}runOnUiThread(()->{if(session!=null&&sessions.contains(session)){switchDocument(session);placeOrDrop("image",name);}});}catch(Exception error){runOnUiThread(()->toast("이미지 가져오기 실패: "+error.getMessage()));}},"image-paste").start();}
    // ================================================================== bottom-sheet menus
    private static final String[] CATEGORY_TITLES={"문서","보기·이동","필기·삽입","학습·주석","내보내기·백업"};
    private static final int[] INK_COLORS={0xFF1C1C1E,0xFF636366,0xFF007AFF,0xFF16835B,0xFF7C3AED,0xFFEA580C,0xFFDB2777,0xFFFF3B30};
    private static final int[] INK_COLORS2={0xFF8E1B14,0xFFB35900,0xFF8A6D00,0xFF00746E,0xFF0040A8,0xFF2E2C8A,0xFFFF9AA2,0xFFA6CBFF};
    private static final float[] INK_WIDTHS={0.0022f,0.004f,0.0065f,0.009f};
    private static final int[] HIGHLIGHT_COLORS={0x66FFDE59,0x6654C27A,0x66FF6B9A,0x66549CF5,0x66B67CF2};
    private static final int[] TEXT_COLORS={0xFF1C1C1E,0xFF8E8E93,0xFF007AFF,0xFF16835B,0xFFEA580C,0xFFFF3B30,0xFFDB2777,0xFF7C3AED};
    private static final String[] FONT_IDS={"sans","medium","light","black","condensed","serif","mono","typewriter","hand","casual"};
    private static final String[] FONT_NAMES={"고딕 (기본)","고딕 중간","고딕 얇게","고딕 굵게","고딕 좁게","명조","고정폭","타자기","손글씨","캐주얼"};
    private static final int TEXT_PAGE_POINTS=595;

    private static final class Tile{
        final String label;final int icon;final Runnable action;boolean selected,keepOpen;int tint;
        Tile(String label,int icon,Runnable action){this.label=label;this.icon=icon;this.action=action;}
        Tile selected(boolean value){selected=value;return this;}
        Tile tint(int value){tint=value;return this;}
        Tile keepOpen(){keepOpen=true;return this;}
    }
    private static final class Section{
        final String title;final List<Tile> tiles=new ArrayList<>();View custom;
        Section(String title){this.title=title;}
        Section add(Tile tile){tiles.add(tile);return this;}
    }
    private static final class MaxHeightScroll extends ScrollView{
        private final int maxHeight;
        MaxHeightScroll(Context context,int maxHeight){super(context);this.maxHeight=maxHeight;}
        @Override protected void onMeasure(int widthSpec,int heightSpec){super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));}
    }

    /** Menu card in the same look as the anchored menus: grouped rows (small icon, label, check), gray section captions, hairline between groups. */
    private Dialog showSheet(String title,List<Section> sections){
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        FrameLayout root=new FrameLayout(this);root.setTag("menu_sheet");
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setTag("anchored_menu");
        GradientDrawable bg=round(Color.WHITE,18);bg.setStroke(Math.max(1,dp(1)/2),0x14000000);card.setBackground(bg);card.setPadding(dp(6),dp(6),dp(6),dp(6));card.setElevation(dp(12));
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(13);heading.setTextColor(0xFF8E8E93);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(12),dp(8),dp(12),dp(4));card.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        MaxHeightScroll scroll=new MaxHeightScroll(this,Math.round(getResources().getDisplayMetrics().heightPixels*.72f));scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        for(int s=0;s<sections.size();s++){
            Section section=sections.get(s);
            if(s>0){View line=new View(this);line.setBackgroundColor(0xFFE5E5EA);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2));lp.setMargins(dp(12),dp(4),dp(12),dp(4));body.addView(line,lp);}
            if(section.title!=null){TextView label=new TextView(this);label.setText(section.title);label.setTextSize(12);label.setTextColor(0xFF8E8E93);label.setPadding(dp(12),dp(6),dp(12),dp(2));body.addView(label);}
            if(section.custom!=null)body.addView(section.custom,new LinearLayout.LayoutParams(-1,-2));
            for(Tile tile:section.tiles)body.addView(menuRow(dialog,tile),new LinearLayout.LayoutParams(-1,-2));
        }
        card.addView(scroll,new LinearLayout.LayoutParams(-1,-2));root.addView(card,new FrameLayout.LayoutParams(-1,-2));
        dialog.setContentView(root);dialog.setCanceledOnTouchOutside(true);dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));window.setGravity(Gravity.CENTER);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(40),dp(340)),ViewGroup.LayoutParams.WRAP_CONTENT);}
        return dialog;
    }
    private View menuRow(Dialog dialog,Tile tile){
        LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(12),dp(5),dp(12),dp(5));line.setMinimumHeight(dp(44));line.setContentDescription(tile.label);
        if(tile.icon!=0){ImageView glyph=new ImageView(this);glyph.setImageResource(tile.icon);glyph.setColorFilter(tile.tint!=0?tile.tint:ACCENT);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(20),dp(20));gp.rightMargin=dp(12);line.addView(glyph,gp);}
        TextView name=new TextView(this);name.setText(tile.label);name.setTextSize(15);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setTextColor(tile.selected?ACCENT:0xFF1C1C1E);if(tile.selected)name.setTypeface(Typeface.DEFAULT_BOLD);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        if(tile.selected){ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_check_bold);mark.setColorFilter(ACCENT);line.addView(mark,new LinearLayout.LayoutParams(dp(18),dp(18)));}
        line.setOnClickListener(v->{if(!tile.keepOpen)dialog.dismiss();tile.action.run();});
        return line;
    }
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose){return swatches(colors,current,choose,32);}
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose,int size){return swatches(colors,current,choose,size,0);}
    /** @param more 0 = presets only, 1 = adds a rainbow chip that opens the free colour chooser, 2 = the same with an opacity slider */
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose,int size,int more){return swatches(colors,current,choose,size,more,colors);}
    /** @param known every preset colour of the whole palette (all rows): the rainbow chip only counts as "custom" when the colour is in none of them, so two rows never show a check at once. */
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose,int size,int more,int[] known){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(4),dp(2),dp(4),dp(6));
        List<ImageView> dots=new ArrayList<>();final Runnable[] after={null};
        Runnable refresh=()->{for(int i=0;i<dots.size();i++){
            boolean on=colors[i]==current.getAsInt();GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);shape.setColor(colors[i]|0xFF000000);shape.setStroke(dp(on?3:1),on?NAVY:0xFFD5DCE6);
            ImageView dot=dots.get(i);dot.setBackground(shape);if(on){dot.setImageResource(R.drawable.ic_check);dot.setColorFilter(Color.WHITE);}else dot.setImageDrawable(null);}if(after[0]!=null)after[0].run();};
        for(int i=0;i<colors.length;i++){
            final int color=colors[i];ImageView dot=new ImageView(this);dot.setScaleType(ImageView.ScaleType.CENTER);dot.setPadding(dp(5),dp(5),dp(5),dp(5));dot.setContentDescription("색상 "+(i+1));
            dot.setOnClickListener(v->{choose.accept(color);refresh.run();});dots.add(dot);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(size),dp(size));p.setMargins(dp(size<30?3:3),0,dp(3),0);row.addView(dot,p);
        }
        if(more>0){
            final ImageView chip=new ImageView(this);chip.setScaleType(ImageView.ScaleType.CENTER);chip.setContentDescription("다른 색 선택");
            final Runnable chipLook=()->{int now=current.getAsInt();boolean custom=true;for(int c:known)if(c==now){custom=false;break;}
                GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);
                if(custom){shape.setColor(now);shape.setStroke(dp(3),NAVY);chip.setImageResource(R.drawable.ic_check);chip.setColorFilter(Color.WHITE);}
                else{shape.setGradientType(GradientDrawable.SWEEP_GRADIENT);shape.setColors(new int[]{0xFFFF3B30,0xFFFFCC00,0xFF34C759,0xFF00C7BE,0xFF007AFF,0xFFAF52DE,0xFFFF3B30});shape.setStroke(dp(1),0xFFD5DCE6);chip.setImageDrawable(null);}
                chip.setBackground(shape);};
            chip.setOnClickListener(v->ColorPicker.show(this,"색 선택",current.getAsInt(),more==2,c->{choose.accept(c);refresh.run();}));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(size),dp(size));p.setMargins(dp(3),0,dp(3),0);row.addView(chip,p);
            after[0]=chipLook;
        }
        refresh.run();return row;
    }
    private interface CellPainter{void paint(Canvas canvas,float width,float height,int index);}
    /** A row of icon-only choices; each cell is drawn by the painter and the chosen one gets a blue pill behind it. */
    private LinearLayout iconSegmented(int count,java.util.function.IntSupplier current,java.util.function.IntConsumer choose,CellPainter painter){
        LinearLayout row=new LinearLayout(this);row.setPadding(dp(4),dp(4),dp(4),dp(6));final List<View> cells=new ArrayList<>();
        Runnable refresh=()->{for(int i=0;i<cells.size();i++)cells.get(i).setBackground(round(i==current.getAsInt()?0xFFD6E6FF:0xFFF2F2F7,18));};
        for(int i=0;i<count;i++){final int index=i;View cell=new View(this){@Override protected void onDraw(Canvas c){painter.paint(c,getWidth(),getHeight(),index);}};
            cell.setContentDescription(AnnotationPainter.PEN_NAMES.length==count?AnnotationPainter.PEN_NAMES[i]:"굵기 "+(i+1));cell.setOnClickListener(v->{choose.accept(index);refresh.run();});cells.add(cell);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.setMargins(dp(3),0,dp(3),0);row.addView(cell,p);}
        refresh.run();return row;
    }
    private LinearLayout segmented(String[] labels,java.util.function.IntSupplier current,java.util.function.IntConsumer choose){
        LinearLayout row=new LinearLayout(this);row.setPadding(dp(4),dp(4),dp(4),dp(6));
        List<TextView> chips=new ArrayList<>();
        Runnable refresh=()->{for(int i=0;i<chips.size();i++){boolean on=i==current.getAsInt();TextView chip=chips.get(i);chip.setBackground(round(on?ACCENT:0xFFF2F2F7,18));chip.setTextColor(on?Color.WHITE:NAVY);chip.setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}};
        for(int i=0;i<labels.length;i++){
            final int index=i;TextView chip=new TextView(this);chip.setText(labels[i]);chip.setTextSize(13);chip.setGravity(Gravity.CENTER);chip.setSingleLine();
            chip.setOnClickListener(v->{choose.accept(index);refresh.run();});chips.add(chip);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(36),1);p.setMargins(dp(3),0,dp(3),0);row.addView(chip,p);
        }
        refresh.run();return row;
    }
    private TextView toggleChip(String label,int typefaceStyle,boolean[] flag,Runnable changed){
        TextView chip=new TextView(this);chip.setText(label);chip.setTextSize(15);chip.setGravity(Gravity.CENTER);chip.setTypeface(typefaceStyle==Typeface.ITALIC?Typeface.create(Typeface.SERIF,Typeface.ITALIC):Typeface.defaultFromStyle(typefaceStyle));
        chip.setTextSize(17);Runnable paint=()->{chip.setBackground(round(flag[0]?ACCENT:0xFFF2F2F7,18));chip.setTextColor(flag[0]?Color.WHITE:NAVY);};
        chip.setOnClickListener(v->{flag[0]=!flag[0];paint.run();changed.run();});paint.run();return chip;
    }
    /** Same on/off chip as {@link #toggleChip} but showing a drawn icon (a slanted "I" with its top and bottom bars for italic). */
    private ImageView toggleIcon(int drawable,boolean[] flag,Runnable changed){
        ImageView chip=new ImageView(this);chip.setImageResource(drawable);chip.setScaleType(ImageView.ScaleType.FIT_CENTER);chip.setPadding(dp(8),dp(7),dp(8),dp(7));
        Runnable paint=()->{chip.setBackground(round(flag[0]?ACCENT:0xFFF2F2F7,18));chip.setColorFilter(flag[0]?Color.WHITE:NAVY);};
        chip.setOnClickListener(v->{flag[0]=!flag[0];paint.run();changed.run();});paint.run();return chip;
    }
    private Section sectionOf(int category,boolean titled){
        Section section=new Section(titled?CATEGORY_TITLES[category]:null);
        for(Tile tile:categoryTiles(category))section.add(tile);return section;
    }
    private List<Tile> categoryTiles(int category){
        List<Tile> t=new ArrayList<>();
        switch(category){
            case 0:
                t.add(new Tile("문서함·파일 관리",R.drawable.ic_folder_open,this::showLibrary));
                t.add(new Tile("문서 추가",R.drawable.ic_note_add,this::showAddDocumentMenu));
                t.add(new Tile("새 노트",R.drawable.ic_note_add,this::newNotebook));
                t.add(new Tile("현재 문서 이름 변경",R.drawable.ic_rename,()->{if(activeSession!=null)renameDocument(activeSession);else toast("문서를 먼저 여세요");}));
                break;
            case 1:
                t.add(new Tile("페이지 미리보기",R.drawable.ic_sidebar,this::toggleSidebar).selected(sidebarVisible));
                t.add(new Tile("페이지로 이동",R.drawable.ic_page,this::goToPage));
                t.add(new Tile("현재 페이지 뒤에 추가",R.drawable.ic_page_add,()->choosePageToInsert(currentPage)));
                t.add(new Tile("다른 형식으로 페이지 추가",R.drawable.ic_page_add,()->chooseOtherPageFormat(currentPage)));
                t.add(new Tile("페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(currentPage)).tint(0xFFFF3B30));
                t.add(new Tile("두 쪽 보기 · "+(twoPage?"켜짐":"꺼짐"),R.drawable.ic_book,this::toggleTwoPage).selected(twoPage));
                t.add(new Tile("전체 화면",R.drawable.ic_fullscreen,this::toggleFullscreen));
                t.add(new Tile("페이지 넘김 설정",R.drawable.ic_swipe,this::choosePageSwipeDirection));t.add(new Tile("넘김 효과",R.drawable.ic_magic,this::choosePageAnimation));
                t.add(new Tile("읽기·페이지 넘김",R.drawable.ic_swipe,()->setInkMode(0)));
                break;
            case 2:
                t.add(new Tile("필기 모드",R.drawable.ic_ink,()->setWriteMode(true)));
                t.add(new Tile("올가미·영역 캡처",R.drawable.ic_lasso,this::startLasso));
                t.add(new Tile("텍스트 선택",R.drawable.ic_select_text,this::startTextSelection));
                t.add(new Tile("타이핑",R.drawable.ic_text,this::toggleTyping));
                t.add(new Tile("사진·이미지",R.drawable.ic_image,this::insertImage));
                t.add(new Tile("스티커",R.drawable.ic_sticker,this::showStickerPicker));
                t.add(new Tile("동영상",R.drawable.ic_video,this::pickVideo));
                t.add(new Tile("유튜브 링크",R.drawable.ic_youtube,this::askYoutube));
                t.add(new Tile("음성 녹음",R.drawable.ic_mic,this::startRecording));
                t.add(new Tile("메모",R.drawable.ic_memo,this::toggleMemoMode));
                t.add(new Tile("하이라이트",R.drawable.ic_highlight,this::toggleHighlight));
                break;
            case 3:
                t.add(new Tile("문서·필기 검색",R.drawable.ic_search,this::searchDocument));
                t.add(new Tile("듀얼 뷰 노트",R.drawable.ic_dual,()->showStudy(false)));
                t.add(new Tile("발췌 바구니",R.drawable.ic_basket,()->showStudy(true)));
                t.add(new Tile("메모·하이라이트",R.drawable.ic_highlight,this::showMarkList));
                t.add(new Tile("번역 포스트잇",R.drawable.ic_translate,this::showTranslations));
                t.add(new Tile("책갈피",R.drawable.ic_star,this::showBookmarks));
                t.add(new Tile("개요 목록",R.drawable.ic_outline,this::showOutlineList));
                t.add(new Tile("개요 추가",R.drawable.ic_flag,this::toggleOutlineMode));
                t.add(new Tile("글자 다시 인식",R.drawable.ic_scan,()->recognizePageText(true)));
                break;
            default:
                t.add(new Tile("인쇄",R.drawable.ic_print,this::printDocument));
                t.add(new Tile("PDF 내보내기",R.drawable.ic_pdf,this::exportPdf));
                t.add(new Tile("노트·발췌 내보내기",R.drawable.ic_export,this::exportStudy));
                t.add(new Tile("이 문서 필기 백업 저장",R.drawable.ic_backup,this::exportAnnotations));
                t.add(new Tile("이 문서 필기 백업 불러오기",R.drawable.ic_import,this::importSidecar));
                t.add(new Tile("원본 파일 내보내기",R.drawable.ic_original,this::exportOriginal));
                t.add(new Tile("다른 기기와 동기화",R.drawable.ic_sync,this::showDeviceSync));
                t.add(new Tile("모든 문서 백업",R.drawable.ic_backup,this::startLibraryBackup));
                t.add(new Tile("모든 문서 복원",R.drawable.ic_import,this::startLibraryRestore));
                break;
        }
        return t;
    }
    /** One scrolling sheet with every tool group, so any feature is a single tap away. */
    private void showTools(){
        List<Section> sections=new ArrayList<>();
        if(renderer==null)sections.add(sectionOf(0,true));
        else for(int category=0;category<CATEGORY_TITLES.length;category++)sections.add(sectionOf(category,true));
        boolean awake=recentPrefs.getBoolean("keep_awake",false);
        sections.add(new Section("읽기 편의").add(new Tile("화면 켜 둠",R.drawable.ic_clock,()->{recentPrefs.edit().putBoolean("keep_awake",!awake).apply();applyKeepAwake();toast(!awake?"읽는 동안 화면이 꺼지지 않습니다":"화면 자동 꺼짐을 따릅니다");}).selected(awake)).add(new Tile("하단 메뉴 위치·방향",R.drawable.ic_float,()->showBarLayoutMenu(bottomBar))).add(new Tile("전체 화면 메뉴 계속 표시",R.drawable.ic_float,this::toggleDockPinned).selected(dockPinned())));
        sections.add(new Section("도움말").add(new Tile("사용법",R.drawable.ic_outline,this::showHelp)));
        showSheet("메뉴",sections);
    }
    private int widthIndex(){int best=0;for(int i=0;i<INK_WIDTHS.length;i++)if(Math.abs(INK_WIDTHS[i]-inkWidth)<Math.abs(INK_WIDTHS[best]-inkWidth))best=i;return best;}
    // ================================================================== lasso
    private void chooseLassoShape(int shape){
        lassoShape=shape;recentPrefs.edit().putInt("lasso_shape",shape).apply();
        pageView.setLassoShape(shape);syncOtherTools();
        toast(shape==PdfPageView.LASSO_RECT?"네모: 대각선으로 드래그하세요":shape==PdfPageView.LASSO_CIRCLE?"원: 중심에서 바깥쪽으로 드래그하세요":"자유: 원하는 영역을 둘러 그리세요");
    }
    /** Floating menu of the lasso button (same card as the pen panel): the shape to select with, or leave the lasso. */
    private void showLassoMenu(View anchor){
        String[] names={"자유 올가미","네모","원"};int[] icons={R.drawable.ic_lasso,R.drawable.ic_rect,R.drawable.ic_circle};List<AnchoredMenu.Row> rows=new ArrayList<>();
        for(int i=0;i<3;i++){final int shape=i;rows.add(new AnchoredMenu.Row(names[i],icons[i],()->chooseLassoShape(shape)).tint(0xFFAF52DE).selected(lassoShape==i));}
        rows.add(AnchoredMenu.Row.divider());
        rows.add(new AnchoredMenu.Row("올가미 종료",R.drawable.ic_close,()->{setInkMode(0);toast("올가미를 종료했습니다");}).tint(0xFF8E8E93));
        AnchoredMenu.show(this,anchor,true,rows,null);
    }
    /** First tap starts the lasso; tapped again it opens the shape menu. */
    private void toggleLasso(View anchor){
        if(renderer==null){toast("PDF를 먼저 여세요");return;}
        if(pageView.isLassoMode())showLassoMenu(anchor);else startLasso();
    }
    private void startLasso(){
        if(renderer==null){toast("PDF를 먼저 여세요");return;}
        onSelectionAdjustStarted();highlightMode=memoMode=outlineMode=false;placementKind="";inkMode=0;inkHl=false;
        pageView.setLassoShape(lassoShape);pageView.setLassoMode(true);updateToolStates();
        toast(lassoShape==PdfPageView.LASSO_RECT?"드래그해서 네모 영역을 지정하세요. 모양은 올가미 단추를 한 번 더 눌러 바꿀 수 있습니다.":lassoShape==PdfPageView.LASSO_CIRCLE?"중심에서 바깥쪽으로 드래그해 원형 영역을 지정하세요.":"손가락 또는 S펜으로 원하는 영역을 둘러 그리세요. 두 손가락으로 확대할 수 있습니다.");
    }

    // ================================================================== typing directly on the page
    private EditText inlineEdit;private AnnotationStore.PageElement inlineElement;private AnnotationStore inlineStore;private boolean inlineFresh;
    private PdfPageView inlineView;private View inlineMove,inlineResize;private TextView inlineDelete;private View inlineBar;private TextView inlineSize;
    private ViewTreeObserver.OnPreDrawListener inlineTracker;
    private boolean typingActive(){return memoMode&&"text".equals(placementKind);}
    private void toggleTyping(){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(typingActive()){commitInlineText();placementKind="";memoMode=false;pageView.setMemoMode(false);updateToolStates();toast("타이핑을 종료했습니다");return;}
        placeElement("text","");
    }
    private PdfPageView viewForPage(int page){
        if(splitMode&&pageView!=null&&pageView.getVisibility()==View.VISIBLE&&pageView.getPageNumber()==page)return pageView;
        if(firstPageView!=null&&firstPageView.getVisibility()==View.VISIBLE&&firstPageView.getPageNumber()==page)return firstPageView;
        if(secondPageView!=null&&secondPageView.getVisibility()==View.VISIBLE&&secondPageView.getPageNumber()==page)return secondPageView;
        return null;
    }
    private void redrawPages(){for(PdfPageView v:paneViews)if(v!=null)v.invalidate();}
    private AnnotationStore.PageElement elementAt(int page,float x,float y){
        if(store==null)return null;
        for(int i=store.elements.size()-1;i>=0;i--){AnnotationStore.PageElement e=store.elements.get(i);if(e.page==page&&"text".equals(e.kind)&&x>=e.left&&x<=e.right&&y>=e.top&&y<=e.bottom)return e;}
        return null;
    }
    private void loadTextStyle(AnnotationStore.PageElement e){
        e.font=recentPrefs.getString("text_font","sans");if(!AnnotationStore.PageElement.FONTS.contains(e.font))e.font="sans";
        e.bold=recentPrefs.getBoolean("text_bold",false);e.italic=recentPrefs.getBoolean("text_italic",false);
        e.color=recentPrefs.getInt("text_color",AnnotationStore.PageElement.DEFAULT_TEXT_COLOR);
        float size=recentPrefs.getFloat("text_size",AnnotationStore.PageElement.DEFAULT_TEXT_SIZE);e.textSize=size<.004f||size>.3f?AnnotationStore.PageElement.DEFAULT_TEXT_SIZE:size;e.lineSpacing=recentPrefs.getFloat("text_line",0f);if(e.lineSpacing<.8f||e.lineSpacing>4f)e.lineSpacing=0f;
    }
    private void saveTextStyle(AnnotationStore.PageElement e){
        recentPrefs.edit().putString("text_font",e.font).putBoolean("text_bold",e.bold).putBoolean("text_italic",e.italic).putInt("text_color",e.color).putFloat("text_size",e.textSize).putFloat("text_line",e.lineSpacing).apply();
    }
    /** Resizes the box height so the whole text is visible with the element's own width, size and typeface. */
    private void fitTextElement(AnnotationStore.PageElement e){
        PdfPageView view=viewForPage(e.page);float aspect=view!=null?view.pageAspect():1.414f;
        float height=AnnotationPainter.fitHeight(e.text,e.right-e.left,e.textSize,aspect,AnnotationPainter.typeface(e.font,e.bold,e.italic),e.line());
        height=Math.min(.98f,height);if(e.top+height>.99f)e.top=Math.max(0f,.99f-height);e.bottom=e.top+height;
    }
    private int pointsOf(AnnotationStore.PageElement e){return Math.max(8,Math.min(72,Math.round(e.textSize*TEXT_PAGE_POINTS)));}

    /** A new, empty text box whose top-left corner is where the page was tapped. */
    private AnnotationStore.PageElement newTextBox(int page,float x,float y){
        AnnotationStore.PageElement box=new AnnotationStore.PageElement();box.page=page;box.kind="text";loadTextStyle(box);
        box.left=Math.max(0f,Math.min(.72f,x));box.top=Math.max(0f,Math.min(.92f,y));box.right=Math.min(.97f,box.left+.45f);box.bottom=Math.min(.99f,box.top+.06f);return box;
    }
    /** Opens an editor right on the page: the typed text appears exactly where it will be saved. */
    private void beginInlineText(AnnotationStore.PageElement element,boolean fresh){
        commitInlineText();if(store==null)return;
        PdfPageView view=viewForPage(element.page);if(view==null){showPage(element.page);view=viewForPage(element.page);}if(view==null)return;
        inlineElement=element;inlineFresh=fresh;inlineStore=store;inlineView=view;AnnotationPainter.skip=element;redrawPages();
        final AnnotationStore.PageElement e=element;
        inlineEdit=new EditText(this);inlineEdit.setTag("inline_text");inlineEdit.setText(e.text);inlineEdit.setSelection(inlineEdit.getText().length());inlineEdit.setHint("글을 입력하세요");inlineEdit.setHintTextColor(0x66000000|(e.color&0x00FFFFFF));
        inlineEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);inlineEdit.setGravity(Gravity.TOP|Gravity.START);inlineEdit.setIncludeFontPadding(false);inlineEdit.setPadding(dp(3),dp(2),dp(3),dp(2));
        GradientDrawable frame=new GradientDrawable();frame.setColor(0x66FFFFFF);frame.setCornerRadius(dp(4));frame.setStroke(dp(1),ACCENT);inlineEdit.setBackground(frame);inlineEdit.setMinHeight(0);inlineEdit.setMinimumHeight(0);
        inlineEdit.addTextChangedListener(new android.text.TextWatcher(){int newline=-1;boolean busy;public void beforeTextChanged(CharSequence q,int a,int b,int c){}public void onTextChanged(CharSequence q,int a,int b,int c){e.text=q.toString();if(b!=c)showInlineBar(false);newline=(!busy&&b==0&&c==1&&q.charAt(a)=='\n')?a:-1;}
            public void afterTextChanged(android.text.Editable q){if(newline<0||busy)return;int at=newline;newline=-1;busy=true;try{continueList(q,at);}finally{busy=false;}e.text=q.toString();}});
        inlineEdit.setOnTouchListener((v,ev)->{if(ev.getActionMasked()==MotionEvent.ACTION_UP)showInlineBar(true);return false;});
        viewportLayer.addView(inlineEdit,new FrameLayout.LayoutParams(dp(120),-2,Gravity.TOP|Gravity.START));
        inlineMove=inlineHandle("✥","글상자 이동",(dx,dy,page)->{float w=e.right-e.left,h=e.bottom-e.top;float nx=Math.max(0f,Math.min(1f-w,e.left+dx/page.width())),ny=Math.max(0f,Math.min(1f-h,e.top+dy/page.height()));e.left=nx;e.right=nx+w;e.top=ny;e.bottom=ny+h;});
        inlineResize=inlineHandle("↔","글상자 너비",(dx,dy,page)->{e.right=Math.max(e.left+.12f,Math.min(1f,e.right+dx/page.width()));});
        inlineDelete=new TextView(this);inlineDelete.setText("✕");inlineDelete.setTextSize(14);inlineDelete.setTextColor(Color.WHITE);inlineDelete.setGravity(Gravity.CENTER);inlineDelete.setBackground(round(0xFFEF5B7C,15));inlineDelete.setElevation(dp(3));inlineDelete.setContentDescription("글상자 삭제");inlineDelete.setTag("inline_delete");inlineDelete.setOnClickListener(v->deleteInlineText());viewportLayer.addView(inlineDelete,new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.TOP|Gravity.START));
        buildInlineBar(e);applyInlineStyle();positionInlineText();
        inlineTracker=()->{positionInlineText();return true;};viewportLayer.getViewTreeObserver().addOnPreDrawListener(inlineTracker);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);inlineEdit.requestFocus();InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(inlineEdit,InputMethodManager.SHOW_IMPLICIT);
    }
    private interface HandleDrag{void moved(float dx,float dy,RectF page);}
    private View inlineHandle(String glyph,String description,HandleDrag drag){
        TextView handle=new TextView(this);handle.setText(glyph);handle.setTextSize(14);handle.setTextColor(Color.WHITE);handle.setGravity(Gravity.CENTER);handle.setBackground(round(ACCENT,15));handle.setContentDescription(description);handle.setElevation(dp(3));
        final float[] last=new float[2];
        handle.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:last[0]=event.getRawX();last[1]=event.getRawY();v.getParent().requestDisallowInterceptTouchEvent(true);return true;
                case MotionEvent.ACTION_MOVE:{RectF page=inlineView==null?null:inlineView.pageRect();if(page!=null&&page.width()>0&&page.height()>0){drag.moved(event.getRawX()-last[0],event.getRawY()-last[1],page);}last[0]=event.getRawX();last[1]=event.getRawY();return true;}
                default:return true;
            }
        });
        viewportLayer.addView(handle,new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.TOP|Gravity.START));return handle;
    }
    private void positionInlineText(){
        if(inlineEdit==null||inlineElement==null||inlineView==null)return;
        RectF page=inlineView.pageRect();if(page.width()<=0)return;AnnotationStore.PageElement e=inlineElement;
        int[] a=new int[2],b=new int[2];inlineView.getLocationInWindow(a);viewportLayer.getLocationInWindow(b);
        int left=Math.round(a[0]-b[0]+page.left+e.left*page.width()),top=Math.round(a[1]-b[1]+page.top+e.top*page.height()),width=Math.max(dp(80),Math.round((e.right-e.left)*page.width()));
        float px=Math.max(9f,page.width()*e.textSize);
        if(Math.abs(inlineEdit.getTextSize()-px)>.4f||Math.abs(inlineLineApplied-e.line())>.001f){inlineLineApplied=e.line();inlineEdit.setTextSize(TypedValue.COMPLEX_UNIT_PX,px);Paint.FontMetrics fm=inlineEdit.getPaint().getFontMetrics();inlineEdit.setLineSpacing(Math.max(0f,px*e.line()-(fm.descent-fm.ascent)),1f);}
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)inlineEdit.getLayoutParams();
        if(lp.leftMargin!=left||lp.topMargin!=top||lp.width!=width){lp.leftMargin=left;lp.topMargin=top;lp.width=width;inlineEdit.setLayoutParams(lp);}
        int editBottom=top+Math.max(dp(18),inlineEdit.getHeight());
        placeHandle(inlineMove,left-dp(8),top-dp(34));placeHandle(inlineDelete,left+width-dp(22),top-dp(34));placeHandle(inlineResize,left+width-dp(12),editBottom-dp(10));
        placeInlineBar(top,editBottom);
    }
    /** Keeps the toolbar clear of the text being typed: above the box first (the keyboard covers the lower part), else below, else at the top. */
    /** The style bar never wider than the page area (a side panel makes that narrower); smaller widths scroll sideways. */
    private int inlineBarWidth(){int area=viewportLayer!=null&&viewportLayer.getWidth()>0?viewportLayer.getWidth():getResources().getDisplayMetrics().widthPixels;return Math.max(dp(120),Math.min(dp(330),area-dp(16)));}
    /** The format bar hides while typing so it stays out of the way; touching the text box (tap, long press, selection) brings it back. */
    private void showInlineBar(boolean shown){if(inlineBar!=null&&(inlineBar.getVisibility()==View.VISIBLE)!=shown)inlineBar.setVisibility(shown?View.VISIBLE:View.GONE);}
    private void placeInlineBar(int editTop,int editBottom){
        if(inlineBar==null||viewportLayer==null||viewportLayer.getHeight()<=0)return;
        {FrameLayout.LayoutParams wl=(FrameLayout.LayoutParams)inlineBar.getLayoutParams();int bw=inlineBarWidth();if(wl.width!=bw){wl.width=bw;inlineBar.setLayoutParams(wl);}}
        int barH=inlineBar.getHeight()>0?inlineBar.getHeight():dp(42),H=viewportLayer.getHeight(),gap=dp(4);
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)inlineBar.getLayoutParams();
        int topMargin;
        if(editTop-dp(36)-barH-gap>=dp(2))topMargin=editTop-dp(36)-barH-gap;
        else if(H-editBottom-dp(14)>=barH+gap)topMargin=editBottom+dp(14)+gap;
        else topMargin=dp(4);
        topMargin=Math.max(0,Math.min(topMargin,Math.max(0,H-barH)));
        if(lp.topMargin!=topMargin||(lp.gravity&Gravity.VERTICAL_GRAVITY_MASK)!=Gravity.TOP){lp.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;lp.topMargin=topMargin;lp.bottomMargin=0;inlineBar.setLayoutParams(lp);}
    }
    private void placeHandle(View handle,int left,int top){
        if(handle==null)return;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)handle.getLayoutParams();
        if(viewportLayer!=null&&viewportLayer.getWidth()>0){left=Math.max(dp(2),Math.min(left,viewportLayer.getWidth()-lp.width-dp(2)));top=Math.max(dp(2),Math.min(top,viewportLayer.getHeight()-lp.height-dp(2)));}
        if(lp.leftMargin!=left||lp.topMargin!=top){lp.leftMargin=left;lp.topMargin=top;handle.setLayoutParams(lp);}
    }
    private void applyInlineStyle(){
        if(inlineEdit==null||inlineElement==null)return;AnnotationStore.PageElement e=inlineElement;
        inlineEdit.setTypeface(AnnotationPainter.typeface(e.font,e.bold,e.italic));inlineEdit.setTextColor(e.color|0xFF000000);inlineEdit.setGravity(Gravity.TOP|(e.align==1?Gravity.CENTER_HORIZONTAL:e.align==2?Gravity.END:Gravity.START));inlineEdit.setPaintFlags((inlineEdit.getPaintFlags()&~(Paint.UNDERLINE_TEXT_FLAG|Paint.STRIKE_THRU_TEXT_FLAG))|(e.underline?Paint.UNDERLINE_TEXT_FLAG:0)|(e.strike?Paint.STRIKE_THRU_TEXT_FLAG:0));
        if(inlineSize!=null)inlineSize.setText(pointsOf(e)+"pt");positionInlineText();
    }
    private float inlineLineApplied;private TextView inlineLineLabel;
    private void changeInlineLine(float delta){
        if(inlineElement==null)return;float next=Math.round((inlineElement.line()+delta)*20f)/20f;next=Math.max(.8f,Math.min(4f,next));inlineElement.lineSpacing=Math.abs(next-AnnotationStore.PageElement.DEFAULT_LINE)<.001f?0f:next;updateInlineLineLabel();applyInlineStyle();
    }
    private void updateInlineLineLabel(){if(inlineLineLabel!=null&&inlineElement!=null)inlineLineLabel.setText(String.format(Locale.US,"%.2f",inlineElement.line()));}
    /** Picks a ruled paper and sets the line spacing to its rule pitch, then moves the box so the first line sits on the nearest rule. */
    private void chooseNoteRuler(){
        if(inlineElement==null)return;final AnnotationStore.PageElement e=inlineElement;
        List<Integer> kinds=new ArrayList<>();NotebookFiles.Paper own=activeSession!=null&&library.managed(activeSession.uri)?library.paper(new File(activeSession.uri.getPath())):null;
        int ownKind=NotebookFiles.rulerKind(own);if(ownKind>0)kinds.add(ownKind);
        for(int k:new int[]{10,11,12,1,4,5,2,3,7})if(!kinds.contains(k))kinds.add(k);
        CharSequence[] names=new CharSequence[kinds.size()];for(int i=0;i<names.length;i++)names[i]=NotebookFiles.PAPER_NAMES[kinds.get(i)]+(i==0&&ownKind>0?"  (현재 노트)":"");
        new AlertDialog.Builder(this).setTitle("노트 줄에 맞추기").setItems(names,(d,which)->fitInlineToRuler(e,kinds.get(which))).setNegativeButton("취소",null).show();
    }
    private void fitInlineToRuler(AnnotationStore.PageElement e,int kind){
        float[] r=NotebookFiles.ruler(kind);if(r==null)return;
        float sizePt=e.textSize*TEXT_PAGE_POINTS,pitch=r[1];int k=1;while(pitch*k/sizePt<1f&&k<6)k++;
        e.lineSpacing=Math.max(.8f,Math.min(4f,pitch*k/sizePt));
        PdfPageView view=viewForPage(e.page);float aspect=view!=null?view.pageAspect():1.414f,heightPt=TEXT_PAGE_POINTS*aspect;
        float baseline=e.top*heightPt+sizePt;int n=Math.max(0,Math.round((baseline+.22f*sizePt-r[0])/pitch));
        float top=(r[0]+n*pitch-.22f*sizePt-sizePt)/heightPt;float h=e.bottom-e.top;e.top=Math.max(0f,Math.min(.99f-h,top));e.bottom=e.top+h;
        updateInlineLineLabel();applyInlineStyle();fitTextElement(e);positionInlineText();toast("줄간격 "+String.format(Locale.US,"%.2f",e.line())+" · "+NotebookFiles.PAPER_NAMES[kind]+" 줄에 맞춤");
    }
    private Runnable inlineFontLabel;private android.widget.PopupWindow fontPopup;
    private void setInlineFont(String id){if(inlineElement==null||!AnnotationStore.PageElement.FONTS.contains(id))return;inlineElement.font=id;if(inlineFontLabel!=null)inlineFontLabel.run();applyInlineStyle();}
    private void changeInlineSize(int delta){
        if(inlineElement==null)return;int points=Math.max(8,Math.min(72,pointsOf(inlineElement)+delta));inlineElement.textSize=points/(float)TEXT_PAGE_POINTS;applyInlineStyle();
    }
    /** Slim one-row toolbar (Aa · B · I · size · delete · done); the font and colour rows open only when "Aa" is tapped. */
    private void buildInlineBar(AnnotationStore.PageElement e){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(6),dp(2),dp(6),dp(2));card.setTag("inline_style_bar");
        final LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setVisibility(View.GONE);
        panel.addView(formatRow(e),new LinearLayout.LayoutParams(-1,dp(38)));
        final TextView faces=new TextView(this);faces.setTag("text_fonts");faces.setTextSize(14);faces.setTextColor(NAVY);faces.setGravity(Gravity.CENTER_VERTICAL);faces.setPadding(dp(12),0,dp(12),0);faces.setBackground(round(0xFFF2F2F7,18));faces.setContentDescription("글꼴 선택");
        Runnable showFont=()->{int at=Math.max(0,Arrays.asList(FONT_IDS).indexOf(e.font));faces.setText("글꼴  "+FONT_NAMES[at]+"  ▾");faces.setTypeface(AnnotationPainter.typeface(e.font,false,false));};showFont.run();inlineFontLabel=showFont;
        faces.setOnClickListener(v->{List<AnchoredMenu.Row> rows=new ArrayList<>();for(int i=0;i<FONT_IDS.length;i++){final String id=FONT_IDS[i];TextView sample=new TextView(this);sample.setText(FONT_NAMES[i]+"   가나다 ABC abc");sample.setTextSize(16);sample.setTextColor(id.equals(e.font)?ACCENT:NAVY);sample.setTypeface(AnnotationPainter.typeface(id,false,false));sample.setGravity(Gravity.CENTER_VERTICAL);sample.setPadding(dp(16),0,dp(16),0);sample.setMinHeight(dp(44));sample.setOnClickListener(x->{setInlineFont(id);if(fontPopup!=null)fontPopup.dismiss();});rows.add(AnchoredMenu.Row.custom(sample));}fontPopup=AnchoredMenu.show(this,faces,false,rows,null);});
        panel.addView(faces,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout palette=swatches(TEXT_COLORS,()->e.color|0xFF000000,c->{e.color=c|0xFF000000;applyInlineStyle();},26,1);palette.setTag("text_colors");palette.setPadding(0,dp(2),0,dp(2));panel.addView(palette,new LinearLayout.LayoutParams(-1,dp(34)));
        LinearLayout spacing=new LinearLayout(this);spacing.setGravity(Gravity.CENTER_VERTICAL);spacing.setTag("text_line_row");
        TextView spLabel=new TextView(this);spLabel.setText("줄간격");spLabel.setTextSize(13);spLabel.setTextColor(NAVY);spLabel.setPadding(dp(6),0,dp(6),0);spacing.addView(spLabel,new LinearLayout.LayoutParams(-2,dp(32)));
        TextView lm=stepButton("−","줄간격 좁게");lm.setOnClickListener(v->changeInlineLine(-.05f));spacing.addView(lm,new LinearLayout.LayoutParams(dp(30),dp(32)));
        inlineLineLabel=new TextView(this);inlineLineLabel.setTag("text_line");inlineLineLabel.setTextSize(12);inlineLineLabel.setTextColor(NAVY);inlineLineLabel.setGravity(Gravity.CENTER);inlineLineLabel.setTypeface(Typeface.DEFAULT_BOLD);spacing.addView(inlineLineLabel,new LinearLayout.LayoutParams(dp(40),dp(32)));updateInlineLineLabel();
        TextView lp2=stepButton("＋","줄간격 넓게");lp2.setOnClickListener(v->changeInlineLine(.05f));spacing.addView(lp2,new LinearLayout.LayoutParams(dp(30),dp(32)));
        TextView ruler=new TextView(this);ruler.setText("노트 줄에 맞추기");ruler.setTag("text_fit_ruler");ruler.setTextSize(13);ruler.setTextColor(ACCENT);ruler.setGravity(Gravity.CENTER);ruler.setTypeface(Typeface.DEFAULT_BOLD);ruler.setPadding(dp(10),0,dp(10),0);ruler.setBackground(round(0xFFE8F1FF,16));ruler.setContentDescription("노트 줄에 맞추기");ruler.setOnClickListener(v->chooseNoteRuler());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,dp(32));rp.setMargins(dp(8),0,0,0);spacing.addView(ruler,rp);
        panel.addView(spacing,new LinearLayout.LayoutParams(-1,dp(38)));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        final boolean[] bold={e.bold},italic={e.italic};
        TextView style=stepButton("Aa","글꼴·색 펼치기");style.setTextSize(14);style.setTypeface(Typeface.DEFAULT_BOLD);style.setTag("text_style_toggle");
        style.setOnClickListener(v->{boolean open=panel.getVisibility()!=View.VISIBLE;panel.setVisibility(open?View.VISIBLE:View.GONE);style.setBackground(round(open?0xFFD6E6FF:0xFFF2F2F7,18));});
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(38),dp(32));sp.setMargins(dp(2),0,dp(6),0);row.addView(style,sp);
        TextView boldChip=toggleChip("B",Typeface.BOLD,bold,()->{e.bold=bold[0];applyInlineStyle();});boldChip.setTag("text_bold");View italicChip=toggleIcon(R.drawable.ic_italic,italic,()->{e.italic=italic[0];applyInlineStyle();});italicChip.setTag("text_italic");
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(34),dp(32));cp.setMargins(dp(2),0,dp(2),0);row.addView(boldChip,cp);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(34),dp(32));ip.setMargins(dp(2),0,dp(6),0);row.addView(italicChip,ip);
        TextView minus=stepButton("−","글자 작게");minus.setOnClickListener(v->changeInlineSize(-1));row.addView(minus,new LinearLayout.LayoutParams(dp(30),dp(32)));
        inlineSize=new TextView(this);inlineSize.setTag("text_size");inlineSize.setTextSize(12);inlineSize.setTextColor(NAVY);inlineSize.setGravity(Gravity.CENTER);inlineSize.setTypeface(Typeface.DEFAULT_BOLD);row.addView(inlineSize,new LinearLayout.LayoutParams(dp(40),dp(32)));
        TextView plus=stepButton("＋","글자 크게");plus.setOnClickListener(v->changeInlineSize(1));row.addView(plus,new LinearLayout.LayoutParams(dp(30),dp(32)));
        row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        ImageButton remove=icon(R.drawable.ic_delete,"글상자 삭제",0xFFFF3B30,v->deleteInlineText());remove.setPadding(dp(7),dp(7),dp(7),dp(7));row.addView(remove,new LinearLayout.LayoutParams(dp(34),dp(34)));
        ImageButton done=icon(R.drawable.ic_check,"입력 완료",Color.WHITE,v->commitInlineText());done.setTag("text_done");done.setBackground(round(ACCENT,17));done.setPadding(dp(7),dp(7),dp(7),dp(7));LinearLayout.LayoutParams dp2=new LinearLayout.LayoutParams(dp(34),dp(34));dp2.setMargins(dp(4),0,0,0);row.addView(done,dp2);
        card.addView(row,new LinearLayout.LayoutParams(-1,dp(38)));card.addView(panel,new LinearLayout.LayoutParams(-1,-2));
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);scroller.setFillViewport(true);scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);card.setMinimumWidth(dp(300));scroller.addView(card,new FrameLayout.LayoutParams(-1,-2));
        inlineBar=scroller;scroller.setBackground(round(0xF2FFFFFF,20));scroller.setElevation(dp(6));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(inlineBarWidth(),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.setMargins(dp(8),0,dp(8),0);viewportLayer.addView(inlineBar,lp);
    }
    private LinearLayout zoomPanel;private TextView zoomLabel;
    private void stepZoom(float factor){if(pageView==null||renderer==null)return;float next=Math.round((pageView.zoom()+(factor>1f?.05f:-.05f))*20f)/20f;pageView.setZoom(next);}
    private void updateZoomLabel(float scale){if(zoomLabel!=null)zoomLabel.setText(Math.round(scale*100f)+"%");}

    // ---- list markers (bullets, numbers, checklist) typed as plain text so they survive export and the Windows app
    private static final String BULLET="• ",CHECK="☐ ",CHECKED="☑ ";
    private static final java.util.regex.Pattern NUMBER=java.util.regex.Pattern.compile("^(\\d+)\\. ");
    /** Marker kind at the start of a line: 0 none, 1 bullet, 2 number, 3 checklist. */
    private static int markerKind(String line){if(line.startsWith(BULLET))return 1;if(NUMBER.matcher(line).find())return 2;if(line.startsWith(CHECK)||line.startsWith(CHECKED))return 3;return 0;}
    private static int markerLength(String line){switch(markerKind(line)){case 1:return BULLET.length();case 2:{java.util.regex.Matcher m=NUMBER.matcher(line);m.find();return m.end();}case 3:return CHECK.length();default:return 0;}}
    private static String markerFor(int kind,int number){return kind==1?BULLET:kind==2?number+". ":kind==3?CHECK:"";}
    /** Adds, switches or removes the marker on every line touched by the selection (or the cursor line). */
    private void toggleListMarker(int kind){
        if(inlineEdit==null)return;android.text.Editable text=inlineEdit.getText();String all=text.toString();
        int a=Math.max(0,Math.min(inlineEdit.getSelectionStart(),inlineEdit.getSelectionEnd())),b=Math.max(0,Math.max(inlineEdit.getSelectionStart(),inlineEdit.getSelectionEnd()));
        int start=all.lastIndexOf('\n',Math.max(0,a-1));start=a==0?0:(all.charAt(a-1)=='\n'?a:start+1);int end=all.indexOf('\n',b);if(end<0)end=all.length();
        String[] lines=all.substring(start,end).split("\n",-1);boolean allSame=true;for(String line:lines)if(markerKind(line)!=kind)allSame=false;
        boolean allUnchecked=kind==3&&allSame;if(allUnchecked)for(String line:lines)if(!line.startsWith(CHECK))allUnchecked=false;
        StringBuilder out=new StringBuilder();int number=1;
        for(int i=0;i<lines.length;i++){String line=lines[i];String body=line.substring(markerLength(line));if(i>0)out.append('\n');if(allUnchecked)out.append(CHECKED).append(body);else if(allSame)out.append(body);else{out.append(markerFor(kind,number++)).append(body);}}
        text.replace(start,end,out.toString());inlineEdit.setSelection(Math.min(text.length(),start+out.length()));
    }
    /** After Enter inside a list: starts the next item (numbers count up); Enter on an empty item ends the list. */
    private void continueList(android.text.Editable text,int newlineAt){
        int lineStart=newlineAt==0?0:text.toString().lastIndexOf('\n',newlineAt-1)+1;String previous=text.subSequence(lineStart,newlineAt).toString();int kind=markerKind(previous);if(kind==0)return;
        int markerLen=markerLength(previous);
        if(previous.length()==markerLen){text.delete(lineStart,newlineAt+1);return;}
        int number=1;if(kind==2){java.util.regex.Matcher m=NUMBER.matcher(previous);if(m.find())number=Integer.parseInt(m.group(1))+1;}
        text.insert(newlineAt+1,markerFor(kind,number));inlineEdit.setSelection(newlineAt+1+markerFor(kind,number).length());
    }
    /** Small drawn icon for the format row: 0-2 alignment, 3 underline, 4 strike-through, 5 bullets, 6 numbers, 7 checklist. */
    private View formatIcon(int kind,String description,java.util.function.BooleanSupplier on,Runnable action){
        final View[] holder=new View[1];
        View v=new View(this){@Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),d=getResources().getDisplayMetrics().density;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);boolean active=on.getAsBoolean();p.setColor(active?ACCENT:NAVY);p.setStrokeWidth(1.6f*d);p.setStrokeCap(Paint.Cap.ROUND);
            if(kind<=2){float[] widths={.5f,.34f,.46f};for(int i=0;i<4;i++){float len=(i%2==0?.5f:.34f)*w*(1f);float x0=kind==0?w*.25f:kind==1?(w-len)/2f:w*.75f-len;c.drawLine(x0,h*(.3f+.13f*i),x0+len,h*(.3f+.13f*i),p);}}
            else if(kind==3||kind==4){p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(15*d);p.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(kind==3?"U":"S",w/2f,h*.62f,p);p.setStrokeWidth(1.6f*d);if(kind==3)c.drawLine(w*.33f,h*.74f,w*.67f,h*.74f,p);else c.drawLine(w*.3f,h*.5f,w*.7f,h*.5f,p);}
            else{for(int i=0;i<3;i++){float y=h*(.3f+.2f*i);c.drawLine(w*.5f,y,w*.78f,y,p);p.setStyle(Paint.Style.FILL);if(kind==5)c.drawCircle(w*.32f,y,1.7f*d,p);else if(kind==6){p.setTextSize(7*d);p.setTextAlign(Paint.Align.CENTER);c.drawText(String.valueOf(i+1),w*.32f,y+2.5f*d,p);}else{p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f*d);c.drawRect(w*.26f,y-3*d,w*.38f,y+3*d,p);p.setStrokeWidth(1.6f*d);}p.setStyle(Paint.Style.FILL);}}
        }};
        holder[0]=v;v.setContentDescription(description);v.setBackground(round(0xFFF2F2F7,16));v.setOnClickListener(x->{action.run();x.invalidate();if(x.getParent() instanceof ViewGroup){ViewGroup g=(ViewGroup)x.getParent();for(int i=0;i<g.getChildCount();i++)g.getChildAt(i).invalidate();}});
        return v;
    }
    private LinearLayout formatRow(AnnotationStore.PageElement e){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setTag("text_format");row.setPadding(0,dp(2),0,dp(2));
        View[] items={
            formatIcon(0,"왼쪽 정렬",()->e.align==0,()->{e.align=0;applyInlineStyle();}),formatIcon(1,"가운데 정렬",()->e.align==1,()->{e.align=1;applyInlineStyle();}),formatIcon(2,"오른쪽 정렬",()->e.align==2,()->{e.align=2;applyInlineStyle();}),
            formatIcon(3,"밑줄",()->e.underline,()->{e.underline=!e.underline;applyInlineStyle();}),formatIcon(4,"취소선",()->e.strike,()->{e.strike=!e.strike;applyInlineStyle();}),
            formatIcon(5,"글머리 기호",()->inlineEdit!=null&&currentLineKind()==1,()->toggleListMarker(1)),formatIcon(6,"번호 매기기",()->inlineEdit!=null&&currentLineKind()==2,()->toggleListMarker(2)),formatIcon(7,"체크리스트",()->inlineEdit!=null&&currentLineKind()==3,()->toggleListMarker(3))};
        String[] tags={"text_align_left","text_align_center","text_align_right","text_underline","text_strike","text_bullet","text_number","text_check"};
        for(int i=0;i<items.length;i++){items[i].setTag(tags[i]);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(34),1);lp.setMargins(dp(i==3||i==5?6:2),0,dp(2),0);row.addView(items[i],lp);}
        return row;
    }
    private int currentLineKind(){if(inlineEdit==null)return 0;String all=inlineEdit.getText().toString();int at=Math.max(0,inlineEdit.getSelectionStart());int start=at==0?0:all.lastIndexOf('\n',at-1)+1;int end=all.indexOf('\n',start);return markerKind(all.substring(start,end<0?all.length():end));}
    private TextView stepButton(String label,String description){TextView b=new TextView(this);b.setText(label);b.setTextSize(18);b.setTextColor(NAVY);b.setGravity(Gravity.CENTER);b.setContentDescription(description);b.setBackground(round(0xFFF2F2F7,18));return b;}
    private void removeInlineViews(){
        AnnotationPainter.skip=null;
        if(inlineTracker!=null&&viewportLayer!=null)viewportLayer.getViewTreeObserver().removeOnPreDrawListener(inlineTracker);inlineTracker=null;
        for(View v:new View[]{inlineEdit,inlineMove,inlineResize,inlineDelete,inlineBar})if(v!=null)viewportLayer.removeView(v);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(inlineEdit!=null){InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(inlineEdit.getWindowToken(),0);}
        inlineEdit=null;inlineMove=inlineResize=inlineDelete=null;inlineBar=null;inlineSize=null;inlineLineLabel=null;inlineElement=null;inlineStore=null;inlineView=null;
    }
    /** Saves the text being typed (an empty new box is dropped; emptying an old box deletes it). */
    private void commitInlineText(){
        if(inlineElement==null)return;
        AnnotationStore.PageElement e=inlineElement;AnnotationStore target=inlineStore;boolean fresh=inlineFresh;String text=inlineEdit==null?e.text:inlineEdit.getText().toString().trim();
        removeInlineViews();
        if(text.isEmpty()){if(!fresh&&target!=null){target.elements.remove(e);target.save();}}
        else{e.text=text;fitTextElement(e);if(fresh&&target!=null&&!target.elements.contains(e))target.elements.add(e);if(target!=null)target.save();saveTextStyle(e);}
        redrawPages();
    }
    private void deleteInlineText(){
        if(inlineElement==null)return;AnnotationStore.PageElement e=inlineElement;AnnotationStore target=inlineStore;boolean fresh=inlineFresh;removeInlineViews();
        if(!fresh&&target!=null){target.elements.remove(e);target.save();}redrawPages();
    }

    // ================================================================== iOS-style action sheet and editor card
    private static final int[] PAPER_COLORS={0xFFFFF3A6,0xFFFFD6E0,0xFFCFE8FF,0xFFD5F5D0,0xFFFFE0B8,0xFFE6D9FF,0xFFFFFFFF};
    /** Action sheet: rounded white list of centered blue rows plus a separate Cancel card (checked row is bold with a check). */
    private void showActionSheet(String title,String[] labels,int checked,java.util.function.IntConsumer pick){
        AnchoredMenu.showCentered(this,getWindow().getDecorView(),title,choiceRows(labels,checked,pick));
    }
    /** Centered editor card: title, rounded field, optional sticky-note style controls, and an inline button row. */
    private Dialog showMemoEditor(String title,AnnotationStore.Mark mark,String saveLabel,java.util.function.Consumer<String> onSave,String dangerLabel,Runnable onDanger,String extraLabel,Runnable onExtra){
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        final int[] style={mark.paper,mark.fontSp,mark.boxSize};final int origBox=mark.boxSize;
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(round(Color.WHITE,20));card.setPadding(dp(18),dp(18),dp(18),dp(6));card.setTag("memo_editor");
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(18);heading.setTextColor(NAVY);heading.setTypeface(Typeface.DEFAULT_BOLD);card.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        EditText input=new EditText(this);input.setHint("메모를 입력하세요");input.setText(mark.note==null?"":mark.note);input.setMinLines(3);input.setMaxLines(6);input.setGravity(Gravity.TOP|Gravity.START);input.setTextSize(16);input.setTextColor(NAVY);input.setBackground(round(0xFFF2F2F7,12));input.setPadding(dp(14),dp(12),dp(14),dp(12));input.setTag("memo_input");
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.topMargin=dp(12);card.addView(input,ip);
        TextView preview=new TextView(this);
        Runnable applyPreview=()->{preview.setBackground(round(style[0],10));preview.setTextSize(style[1]);preview.setText("미리보기 · "+style[1]+"pt");};
        LinearLayout sizeRow=new LinearLayout(this);sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLabel=new TextView(this);sizeLabel.setText("메모 안 글자 크기");sizeLabel.setTextSize(13);sizeLabel.setTextColor(0xFF8E8E93);sizeRow.addView(sizeLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView minus=stepButton("−","글자 작게");minus.setOnClickListener(v->{style[1]=Math.max(9,style[1]-1);applyPreview.run();});sizeRow.addView(minus,new LinearLayout.LayoutParams(dp(34),dp(32)));
        TextView plus=stepButton("＋","글자 크게");plus.setOnClickListener(v->{style[1]=Math.min(28,style[1]+1);applyPreview.run();});LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(34),dp(32));pp.setMargins(dp(6),0,0,0);sizeRow.addView(plus,pp);
        LinearLayout.LayoutParams sr=new LinearLayout.LayoutParams(-1,-2);sr.topMargin=dp(12);card.addView(sizeRow,sr);
        TextView boxLabel=new TextView(this);boxLabel.setText("메모 상자 크기 (메모를 한 번 탭하면 모서리를 끌어 직접 조절)");boxLabel.setTextSize(13);boxLabel.setTextColor(0xFF8E8E93);LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,-2);bl.topMargin=dp(12);card.addView(boxLabel,bl);
        LinearLayout box=segmented(new String[]{"작게","보통","크게"},()->style[2],i->style[2]=i);box.setTag("memo_box_size");box.setPadding(0,dp(6),0,dp(2));card.addView(box,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout paper=swatches(PAPER_COLORS,()->style[0],c->{style[0]=c;applyPreview.run();},30,2);paper.setTag("memo_paper_colors");paper.setPadding(0,dp(4),0,dp(4));card.addView(paper,new LinearLayout.LayoutParams(-1,dp(42)));
        preview.setTextColor(0xFF3F3A2D);preview.setPadding(dp(12),dp(8),dp(12),dp(8));LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);vp.topMargin=dp(4);card.addView(preview,vp);applyPreview.run();
        View line=new View(this);line.setBackgroundColor(0xFFE5E5EA);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2));lp.topMargin=dp(12);card.addView(line,lp);
        LinearLayout buttons=new LinearLayout(this);buttons.setGravity(Gravity.CENTER_VERTICAL);
        if(extraLabel!=null){buttons.addView(dialogButton(extraLabel,ACCENT,false,()->{dialog.dismiss();onExtra.run();}),new LinearLayout.LayoutParams(-2,dp(48)));}
        buttons.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        if(dangerLabel!=null)buttons.addView(dialogButton(dangerLabel,0xFFFF3B30,false,()->{dialog.dismiss();onDanger.run();}),new LinearLayout.LayoutParams(-2,dp(48)));
        buttons.addView(dialogButton("취소",0xFF8E8E93,false,dialog::dismiss),new LinearLayout.LayoutParams(-2,dp(48)));
        buttons.addView(dialogButton(saveLabel,ACCENT,true,()->{mark.paper=style[0];mark.fontSp=style[1];if(style[2]!=origBox){mark.boxW=mark.boxH=0;}mark.boxSize=style[2];dialog.dismiss();onSave.accept(input.getText().toString().trim());}),new LinearLayout.LayoutParams(-2,dp(48)));
        card.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.addView(card);
        dialog.setContentView(scroll);dialog.setCanceledOnTouchOutside(true);dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setGravity(Gravity.CENTER);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(32),dp(420)),ViewGroup.LayoutParams.WRAP_CONTENT);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        return dialog;
    }
    private TextView dialogButton(String label,int color,boolean bold,Runnable action){TextView b=new TextView(this);b.setText(label);b.setTextSize(16);b.setTextColor(color);if(bold)b.setTypeface(Typeface.DEFAULT_BOLD);b.setGravity(Gravity.CENTER);b.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);b.setIncludeFontPadding(false);b.setSingleLine();b.setPadding(dp(12),0,dp(12),0);b.setContentDescription(label);b.setOnClickListener(v->action.run());return b;}

    // ================================================================== side panel: search / page previews / outline / recordings
    private LinearLayout sidePanel,outlineList,recordingList,insertList;private FrameLayout sideContent;private TextView sideTitle;private ImageButton sideMore;private ScrollView outlineScroll,recordingScroll,insertScroll;
    private final ImageButton[] sideTabs=new ImageButton[5];private int panelTab=1;
    private static final String[] SIDE_TITLES={"검색","미리보기","개요","음성 녹음","삽입 목록"};
    private static final int[] SIDE_TINTS={0xFF30B0C7,0xFF007AFF,0xFF007AFF,0xFFFF3B30,0xFF5856D6};
    private static final int[] SIDE_ICONS={R.drawable.ic_search,R.drawable.ic_thumbnails,R.drawable.ic_outline,R.drawable.ic_mic,R.drawable.ic_link};
    private void buildSidePanel(){
        sidePanel=new LinearLayout(this);sidePanel.setTag("side_panel");sidePanel.setOrientation(LinearLayout.VERTICAL);sidePanel.setBackgroundColor(0xFFF8F8F8);sidePanel.setVisibility(View.GONE);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(12),0,dp(0),0);
        sideTitle=new TextView(this);sideTitle.setTag("side_title");sideTitle.setTextSize(15);sideTitle.setTextColor(NAVY);sideTitle.setTypeface(Typeface.DEFAULT_BOLD);sideTitle.setSingleLine();sideTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);sideTitle.setGravity(Gravity.CENTER_VERTICAL);head.addView(sideTitle,new LinearLayout.LayoutParams(0,dp(48),1));
        sideMore=icon(R.drawable.ic_more_vert,"미리보기 메뉴",NAVY,v->showThumbnailMenu(v));sideMore.setTag("side_more");sideMore.setPadding(dp(7),dp(12),dp(7),dp(12));head.addView(sideMore,new LinearLayout.LayoutParams(dp(36),dp(48)));
        ImageButton closePanel=icon(R.drawable.ic_close,"패널 닫기",NAVY,v->closeSidePanel());closePanelButton=closePanel;closePanel.setPadding(dp(9),dp(12),dp(9),dp(12));head.addView(closePanel,new LinearLayout.LayoutParams(dp(38),dp(48)));
        sidePanel.addView(head,new LinearLayout.LayoutParams(-1,dp(48)));sidePanel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l>0)fitSideHeader(r-l);});
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(8),0,dp(8),dp(6));String[] names={"검색 탭","페이지 미리보기 탭","개요 탭","음성 녹음 탭","삽입 목록 탭"};
        for(int i=0;i<5;i++){final int tab=i;ImageButton b=icon(SIDE_ICONS[i],names[i],NAVY,v->selectPanelTab(tab));b.setPadding(dp(8),dp(8),dp(8),dp(8));b.setTag("side_tab:"+i);sideTabs[i]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(dp(2),0,dp(2),0);tabs.addView(b,lp);}
        sidePanel.addView(tabs,new LinearLayout.LayoutParams(-1,dp(46)));
        sideContent=new FrameLayout(this);sideContent.addView(searchPanel,new FrameLayout.LayoutParams(-1,-1));sideContent.addView(thumbnailPanel,new FrameLayout.LayoutParams(-1,-1));
        outlineList=new LinearLayout(this);outlineList.setOrientation(LinearLayout.VERTICAL);outlineList.setPadding(dp(10),dp(4),dp(10),dp(14));outlineScroll=new ScrollView(this);outlineScroll.addView(outlineList,new ScrollView.LayoutParams(-1,-2));sideContent.addView(outlineScroll,new FrameLayout.LayoutParams(-1,-1));
        recordingList=new LinearLayout(this);recordingList.setOrientation(LinearLayout.VERTICAL);recordingList.setPadding(dp(10),dp(4),dp(10),dp(14));recordingScroll=new ScrollView(this);recordingScroll.addView(recordingList,new ScrollView.LayoutParams(-1,-2));sideContent.addView(recordingScroll,new FrameLayout.LayoutParams(-1,-1));
        insertList=new LinearLayout(this);insertList.setOrientation(LinearLayout.VERTICAL);insertList.setPadding(dp(10),dp(4),dp(10),dp(14));insertScroll=new ScrollView(this);insertScroll.addView(insertList,new ScrollView.LayoutParams(-1,-2));sideContent.addView(insertScroll,new FrameLayout.LayoutParams(-1,-1));
        sidePanel.addView(sideContent,new LinearLayout.LayoutParams(-1,0,1));
    }
    /** Same icon as the matching menu entry (star while only favourites are shown) + the word. */
    private void setSideTitle(int tab){
        boolean star=tab==1&&!showAllThumbnails&&!thumbInk;android.graphics.drawable.Drawable d=getResources().getDrawable(star?R.drawable.ic_star:SIDE_ICONS[tab],getTheme()).mutate();
        d.setTint(star?0xFFF5A623:SIDE_TINTS[tab]);d.setBounds(0,0,dp(20),dp(20));sideTitle.setText(SIDE_TITLES[tab]);sideTitle.setCompoundDrawables(d,null,null,null);sideTitle.setCompoundDrawablePadding(dp(6));sideTitle.post(this::fitSideTitleText);
    }
    /** Shrinks the title (15sp down to 8sp) until icon and word fit; auto-size ignores the icon, so it is measured here. */
    private void fitSideTitleText(){
        if(sideTitle==null||sideTitle.getWidth()<=0)return;float room=sideTitle.getWidth()-sideTitle.getCompoundPaddingLeft()-sideTitle.getCompoundPaddingRight();
        android.graphics.Paint paint=new android.graphics.Paint(sideTitle.getPaint());float density=getResources().getDisplayMetrics().scaledDensity,size=15f;
        for(;size>8f;size-=.5f){paint.setTextSize(size*density);if(paint.measureText(sideTitle.getText().toString())<=room)break;}
        if(Math.abs(sideTitle.getTextSize()/density-size)>.1f)sideTitle.setTextSize(size);
    }
    /** The header never loses its title: narrow panels get smaller buttons and padding. */
    private void fitSideHeader(int width){
        boolean narrow=width<dp(190);int button=narrow?dp(28):dp(36),close=narrow?dp(30):dp(38);
        LinearLayout.LayoutParams m=(LinearLayout.LayoutParams)sideMore.getLayoutParams();LinearLayout.LayoutParams c=(LinearLayout.LayoutParams)closePanelButton.getLayoutParams();
        if(m.width!=button||c.width!=close){m.width=button;c.width=close;sideMore.setPadding(narrow?dp(3):dp(7),dp(12),narrow?dp(3):dp(7),dp(12));closePanelButton.setPadding(narrow?dp(5):dp(9),dp(12),narrow?dp(5):dp(9),dp(12));sideMore.setLayoutParams(m);closePanelButton.setLayoutParams(c);}
        ((LinearLayout)sideTitle.getParent()).setPadding(narrow?dp(8):dp(12),0,0,0);sideTitle.post(this::fitSideTitleText);fitSearchRow(width);
    }
    private ImageButton closePanelButton;private LinearLayout searchRow,searchNav;
    /** In a narrow panel the search box gets its own full-width line and the ▲ ▼ ✕ buttons move below it, instead of squeezing the box. */
    private void fitSearchRow(int width){
        if(searchRow==null||searchNav==null)return;boolean stacked=width<dp(290);if((searchRow.getOrientation()==LinearLayout.VERTICAL)==stacked)return;
        searchRow.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        searchInput.setLayoutParams(stacked?new LinearLayout.LayoutParams(-1,dp(40)):new LinearLayout.LayoutParams(0,dp(40),1));
        searchNav.setLayoutParams(stacked?new LinearLayout.LayoutParams(-1,dp(40)):new LinearLayout.LayoutParams(-2,dp(40)));
    }
    private void selectPanelTab(int tab){
        panelTab=tab;sidebarVisible=true;sidePanel.setVisibility(View.VISIBLE);if(sideResizer!=null)sideResizer.setVisibility(View.VISIBLE);
        searchPanel.setVisibility(tab==0?View.VISIBLE:View.GONE);thumbnailPanel.setVisibility(tab==1?View.VISIBLE:View.GONE);outlineScroll.setVisibility(tab==2?View.VISIBLE:View.GONE);recordingScroll.setVisibility(tab==3?View.VISIBLE:View.GONE);insertScroll.setVisibility(tab==4?View.VISIBLE:View.GONE);
        setSideTitle(tab);sideMore.setVisibility(tab==1?View.VISIBLE:View.GONE);
        for(int i=0;i<5;i++){boolean on=i==tab;sideTabs[i].setColorFilter(on?ACTIVE_FG:NAVY);sideTabs[i].setBackground(on?round(ACTIVE_BG,14):null);}
        LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)sidePanel.getLayoutParams();int width=sidePanelWidth();if(lp.width!=width){lp.width=width;sidePanel.setLayoutParams(lp);}
        if(tab==0){searchInput.requestFocus();InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT);}else hideKeyboard();
        rebuildThumbnails();applySearchHighlights();
    }
    /** One width for every tab so the panel never jumps when switching between search, previews, outline and recordings. */
    private int sidePanelWidth(){int saved=recentPrefs==null?0:recentPrefs.getInt("side_width_dp",0);if(saved>0)return clampSideWidth(dp(saved));return Math.min(dp(190),Math.round(getResources().getDisplayMetrics().widthPixels*.42f));}
    private int clampSideWidth(int width){return Math.max(dp(140),Math.min(width,Math.round(getResources().getDisplayMetrics().widthPixels*.7f)));}
    private View sideResizer;
    private void closeSidePanel(){boolean searched=panelTab==0&&!searchHits.isEmpty();sidebarVisible=false;sidePanel.setVisibility(View.GONE);if(sideResizer!=null)sideResizer.setVisibility(View.GONE);closeSearch();hideKeyboard();applySearchHighlights();if(searched)for(PdfPageView v:new PdfPageView[]{pageView,firstPageView,secondPageView})if(v!=null&&v.zoom()!=1f)v.setZoom(1f);}
    /** Icon-only floating menu for the preview panel: favorites only, all pages, add page, delete page. */
    private void showThumbnailMenu(View anchor){
        AnchoredMenu.show(this,anchor,false,AnchoredMenu.rows(
            new AnchoredMenu.Row("즐겨찾기 페이지만",R.drawable.ic_star,()->{setThumbMode(0);selectPanelTab(1);}).tint(0xFFF5A623).selected(!showAllThumbnails&&!thumbInk),
            new AnchoredMenu.Row("전체 페이지",R.drawable.ic_thumbnails,()->{setThumbMode(1);selectPanelTab(1);}).tint(0xFF007AFF).selected(showAllThumbnails&&!thumbInk),
            new AnchoredMenu.Row("필기 있는 페이지만",R.drawable.ic_ink,()->{setThumbMode(2);selectPanelTab(1);}).tint(0xFF5856D6).selected(thumbInk),
            AnchoredMenu.Row.divider(),
            new AnchoredMenu.Row("페이지 추가",R.drawable.ic_page_add,this::chooseAddedPage).tint(0xFF34C759),
            new AnchoredMenu.Row("다른 형식으로 페이지 추가",R.drawable.ic_page_add,()->chooseOtherPageFormat(renderer==null?0:renderer.getPageCount()-1)).tint(0xFF34C759),
            new AnchoredMenu.Row("페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(currentPage)).danger(),
            AnchoredMenu.Row.divider(),
            new AnchoredMenu.Row("이 페이지 필기·삽입 모두 지우기",R.drawable.ic_eraser,()->confirmClearAnnotations(currentPage)).danger(),
            new AnchoredMenu.Row("문서 전체 필기·삽입 모두 지우기",R.drawable.ic_eraser,()->confirmClearAnnotations(-1)).danger()),null);
    }
    /** 0 favourites only, 1 every page, 2 only pages that have handwriting (pen or highlighter strokes). */
    private void setThumbMode(int mode){showAllThumbnails=mode==1;thumbInk=mode==2;recentPrefs.edit().putBoolean("thumb_all",mode==1).putBoolean("thumb_ink",mode==2).apply();}
    /**
     * Removes everything written or inserted on one page (page &gt;= 0) or on the whole document (page &lt; 0) after asking:
     * pen and highlighter strokes, text highlights and memos, and inserted elements (pictures, shapes, tables, typed text, links...). Voice recordings are kept. It cannot be undone.
     */
    private void confirmClearAnnotations(final int page){
        if(renderer==null||store==null)return;final AnnotationStore target=store;
        new AlertDialog.Builder(this).setTitle(page<0?"문서 전체 필기·삽입 지우기":"페이지 필기·삽입 지우기")
            .setMessage((page<0?"이 문서 전체":(page+1)+"페이지")+"의 필기, 형광펜, 하이라이트, 메모와 삽입한 사진·도형·표·타이핑·링크를 모두 지웁니다. 되돌릴 수 없습니다. 계속할까요?")
            .setPositiveButton("모두 지우기",(d,w)->{
                boolean changed=target.strokes.removeIf(x->page<0||x.page==page);
                changed|=target.marks.removeIf(x->page<0||x.page==page);
                changed|=target.elements.removeIf(x->!x.kind.equals("audio")&&(page<0||x.page==page));
                if(activeSession!=null){activeSession.redoStrokes.clear();activeSession.clearedStrokes.clear();activeSession.clearedMarks.clear();activeSession.clearedPage=-1;}
                if(changed)target.save();for(PdfPageView v:paneViews)if(v!=null)v.selectElement(null);redrawPages();refreshStudyPanel();if(sidebarVisible)rebuildThumbnails();
                toast(changed?"필기와 삽입 항목을 지웠습니다":"지울 항목이 없습니다");}).setNegativeButton("취소",null).show();
    }
    private TextView pill(String text,String description,int background,int foreground,View.OnClickListener action){TextView b=new TextView(this);b.setText(text);b.setTextSize(14);b.setTextColor(foreground);b.setTypeface(Typeface.DEFAULT_BOLD);b.setGravity(Gravity.CENTER);b.setContentDescription(description);b.setBackground(round(background,18));b.setOnClickListener(action);return b;}
    /** Lets a list row be swiped away (either direction) to delete it; taps and vertical scrolling keep working. */
    /** Red "delete" strip revealed behind a row while it is swiped (like the iOS Mail list); it turns darker and the trash icon follows the finger once the swipe is far enough to delete. */
    private final class SwipeBackground extends android.graphics.drawable.Drawable{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final android.graphics.drawable.Drawable icon;float dx;boolean armed;
        SwipeBackground(){android.graphics.drawable.Drawable d=getResources().getDrawable(R.drawable.ic_delete,getTheme()).mutate();d.setTint(Color.WHITE);icon=d;}
        @Override public void draw(Canvas canvas){
            android.graphics.Rect b=getBounds();if(b.width()<=0)return;paint.setColor(armed?0xFFD92D20:0xFFFF3B30);float r=dp(16);canvas.drawRoundRect(new RectF(b),r,r,paint);
            int size=dp(22),pad=dp(18);if(b.width()<size+pad)return;
            boolean left=dx<0;int ix=left?(armed?b.left+pad:b.right-pad-size):(armed?b.right-pad-size:b.left+pad);int cy=b.centerY(),s=armed?size+dp(3):size;
            canvas.save();canvas.clipRect(b);icon.setBounds(ix-(s-size)/2,cy-s/2,ix-(s-size)/2+s,cy+s/2);icon.draw(canvas);canvas.restore();
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter){}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void swipeToDelete(View row,Runnable delete){
        final float[] start=new float[2];final boolean[] dragging={false};final int slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();final SwipeBackground[] bg={null};
        final Runnable[] sync={null};
        row.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:start[0]=e.getRawX();start[1]=e.getRawY();dragging[0]=false;return false;
                case MotionEvent.ACTION_MOVE:{float dx=e.getRawX()-start[0],dy=e.getRawY()-start[1];
                    if(!dragging[0]&&Math.abs(dx)>slop*1.5f&&Math.abs(dx)>Math.abs(dy)*1.4f){dragging[0]=true;v.setPressed(false);if(v.getParent()!=null)v.getParent().requestDisallowInterceptTouchEvent(true);
                        if(v.getParent() instanceof ViewGroup){bg[0]=new SwipeBackground();((ViewGroup)v.getParent()).getOverlay().add(bg[0]);
                            sync[0]=()->{SwipeBackground g=bg[0];if(g==null)return;float t=v.getTranslationX();g.dx=t;boolean was=g.armed;g.armed=Math.abs(t)>v.getWidth()*.4f;if(g.armed&&!was)v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                                int l=v.getLeft(),r=v.getRight();g.setBounds(t>0?l:r+Math.round(t),v.getTop(),t>0?l+Math.round(t):r,v.getBottom());g.invalidateSelf();};}}
                    if(dragging[0]){v.setTranslationX(dx);if(sync[0]!=null)sync[0].run();return true;}return false;}
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                    if(dragging[0]){dragging[0]=false;float dx=v.getTranslationX();boolean gone=e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(dx)>v.getWidth()*.4f;
                        final Runnable finish=()->{if(bg[0]!=null&&v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).getOverlay().remove(bg[0]);bg[0]=null;};
                        if(gone)v.animate().translationX(Math.signum(dx)*v.getWidth()).setDuration(160).setUpdateListener(a->{if(sync[0]!=null)sync[0].run();}).withEndAction(()->{finish.run();delete.run();}).start();
                        else v.animate().translationX(0).setDuration(180).setUpdateListener(a->{if(sync[0]!=null)sync[0].run();}).withEndAction(finish).start();return true;}
                    return false;
            }
            return false;
        });
    }
    private void rebuildOutlinePanel(){rebuildOutlineItems();}
    /** Lists every highlight (with or without a note) and every sticky memo in the 삽입 목록 tab (the outline tab keeps outlines only). */
    private void appendMarkList(){
        if(store==null)return;List<AnnotationStore.Mark> listed=new ArrayList<>(store.marks);if(listed.isEmpty())return;
        TextView heading=new TextView(this);heading.setText("메모·하이라이트 "+listed.size());heading.setTextSize(12);heading.setTextColor(0xFF8E8E93);heading.setPadding(dp(6),dp(14),0,dp(6));insertList.addView(heading);
        List<AnnotationStore.Mark> marks=new ArrayList<>(listed);marks.sort(Comparator.comparingInt((AnnotationStore.Mark m)->m.page).thenComparingDouble(m->m.top));
        for(AnnotationStore.Mark mark:marks){
            LinearLayout row=new LinearLayout(this);row.setTag("mark_item");row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(round(Color.WHITE,12));
            View dot=new View(this);GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);shape.setColor(mark.noteOnly?mark.paper:(mark.color|0xFF000000));shape.setStroke(dp(1),0x22000000);dot.setBackground(shape);LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(dp(14),dp(14));dp1.rightMargin=dp(10);row.addView(dot,dp1);
            String note=mark.note==null?"":mark.note.trim();TextView text=new TextView(this);text.setText((mark.noteOnly?"메모":"하이라이트")+" (p"+(mark.page+1)+")"+(note.isEmpty()?"":"\n"+note));text.setTextSize(12);text.setTextColor(NAVY);text.setMaxLines(3);text.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            row.setOnClickListener(v->{showPage(mark.page);pageView.post(()->pageView.focusOnPoint((mark.left+mark.right)/2,(mark.top+mark.bottom)/2));});swipeToDelete(row,()->{store.marks.remove(mark);store.save();redrawPages();rebuildInsertions();toast("삭제했습니다");});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);insertList.addView(row,params);
        }
    }
    private void rebuildOutlineItems(){
        outlineList.removeAllViews();if(store==null)return;
        TextView add=pill("＋ 개요 추가","개요 추가",ACTIVE_BG,ACTIVE_FG,v->toggleOutlineMode());add.setTag("outline_add");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(44));ap.bottomMargin=dp(8);outlineList.addView(add,ap);
        List<AnnotationStore.OutlineItem> items=new ArrayList<>(store.outlines);items.sort(Comparator.comparingInt((AnnotationStore.OutlineItem item)->item.page).thenComparingDouble(item->item.y));
        if(items.isEmpty()){TextView empty=new TextView(this);empty.setText("기억할 위치를 제목과 함께 저장하세요.\n위의 ‘개요 추가’를 누른 뒤 본문을 탭합니다.");empty.setTextSize(12);empty.setTextColor(0xFF8E8E93);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(4),dp(18),dp(4),dp(8));outlineList.addView(empty);return;}
        for(AnnotationStore.OutlineItem item:items){
            LinearLayout row=new LinearLayout(this);row.setTag("outline_item");row.setPadding(dp(14),dp(10),dp(2),dp(10));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(round(Color.WHITE,16));
            TextView title=new TextView(this);android.text.SpannableStringBuilder label=new android.text.SpannableStringBuilder(item.title);int from=label.length();label.append("  (p").append(String.valueOf(item.page+1)).append(")");label.setSpan(new android.text.style.RelativeSizeSpan(.78f),from,label.length(),0);label.setSpan(new android.text.style.ForegroundColorSpan(0xFF8E8E93),from,label.length(),0);title.setText(label);title.setTextSize(12.5f);title.setTextColor(NAVY);
            row.addView(title,new LinearLayout.LayoutParams(0,-2,1));row.setOnClickListener(v->{showPage(item.page);pageView.post(()->pageView.focusOnPoint(item.x,item.y));});swipeToDelete(row,()->{store.outlines.remove(item);store.save();rebuildOutlinePanel();toast("개요를 삭제했습니다");});
            row.addView(icon(R.drawable.ic_more_vert,"개요 관리",NAVY,v->showOutlineItem(item,v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);outlineList.addView(row,params);
        }
    }
    private void rebuildRecordings(){
        recordingList.removeAllViews();if(store==null)return;
        TextView record=pill(recorder!=null?"■ 녹음 중 · 눌러서 정지·첨부":"● 녹음 시작","녹음 시작·정지",recorder!=null?0xFFFF3B30:0xFFFFE3E8,recorder!=null?Color.WHITE:0xFFD6304E,v->{if(recorder!=null)stopRecording(true);else startRecording();});record.setTag("record_button");LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(46));rp.bottomMargin=dp(8);recordingList.addView(record,rp);
        List<AnnotationStore.PageElement> clips=new ArrayList<>();for(AnnotationStore.PageElement e:store.elements)if("audio".equals(e.kind))clips.add(e);clips.sort(Comparator.comparingInt((AnnotationStore.PageElement e)->e.page).thenComparingDouble(e->e.top));
        if(clips.isEmpty()){TextView empty=new TextView(this);empty.setText("녹음하면 현재 페이지에 음성 메모가 첨부됩니다.\n페이지의 ‘▶ 녹음’ 표시를 탭하면 재생합니다.");empty.setTextSize(12);empty.setTextColor(0xFF8E8E93);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(4),dp(18),dp(4),dp(8));recordingList.addView(empty);return;}
        for(AnnotationStore.PageElement clip:clips){
            LinearLayout row=new LinearLayout(this);row.setTag("recording_item");row.setPadding(dp(14),dp(10),dp(4),dp(10));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(round(Color.WHITE,16));
            TextView title=new TextView(this);title.setText("▶  "+clip.text+"\n페이지 "+(clip.page+1));title.setTextSize(14);title.setTextColor(NAVY);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            row.setOnClickListener(v->{showPage(clip.page);showAudioPlayer(clip);});swipeToDelete(row,()->deleteRecording(clip));
            row.addView(icon(R.drawable.ic_more_vert,"녹음 관리",NAVY,v->showRecordingMenu(clip,v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);recordingList.addView(row,params);
        }
    }

    // ================================================================== voice recording attached to a page
    private MediaRecorder recorder;private File recordingFile;private long recordingStarted;private int recordingPage;private AnnotationStore recordingStore;private LinearLayout recorderBar;private TextView recorderTime;private static final int REQUEST_RECORD=31;
    private static String clock(long seconds){seconds=Math.max(0,seconds);return (seconds/60)+":"+(seconds%60<10?"0":"")+(seconds%60);}
    // ---- same-Wi-Fi device sync (user triggered; this phone is the server while the card is open)
    private DeviceSync deviceSync;
    private <T> T onUi(java.util.concurrent.Callable<T> job)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())return job.call();
        final Object[] box=new Object[2];final java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(()->{try{box[0]=job.call();}catch(Exception e){box[1]=e;}latch.countDown();});
        if(!latch.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("앱이 응답하지 않습니다");
        if(box[1]!=null)throw (Exception)box[1];@SuppressWarnings("unchecked")T result=(T)box[0];return result;
    }
    private final DeviceSync.Host syncHost=new DeviceSync.Host(){
        private void collect(File dir,String prefix,org.json.JSONArray out)throws Exception{
            for(File f:library.list(dir)){String rel=prefix+f.getName();
                if(f.isDirectory())collect(f,rel+"/",out);
                else{org.json.JSONObject o=new org.json.JSONObject();o.put("path",rel);o.put("size",f.length());o.put("mtime",Math.max(f.lastModified(),AnnotationStore.modified(MainActivity.this,Uri.fromFile(f))));out.put(o);}}}
        @Override public String listLibrary()throws Exception{org.json.JSONArray out=new org.json.JSONArray();collect(library.root,"",out);return out.toString();}
        @Override public File libraryFile(String rel){
            if(rel==null||rel.isEmpty()||rel.length()>400||rel.startsWith("/")||rel.contains("\\")||rel.contains("\0")||!rel.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))return null;
            for(String part:rel.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.startsWith("."))return null;
            File f=new File(library.root,rel);return library.managed(f)?f:null;}
        @Override public boolean isOpen(String rel)throws Exception{
            final File f=libraryFile(rel);if(f==null)return false;
            return onUi(()->{for(DocumentSession s:sessions)if(s.uri!=null&&"file".equals(s.uri.getScheme())&&f.getPath().equals(s.uri.getPath()))return true;return false;});}
        @Override public String exportNote(String rel)throws Exception{
            File f=libraryFile(rel);if(f==null||!f.isFile())return null;
            AnnotationStore st=new AnnotationStore(MainActivity.this);Uri uri=Uri.fromFile(f);st.open(uri);return st.exportJson(uri,f.getName());}
        @Override public void importNote(String rel,String json)throws Exception{
            File f=libraryFile(rel);if(f==null||!f.isFile())throw new IOException("문서가 없습니다");
            AnnotationStore st=new AnnotationStore(MainActivity.this);st.open(Uri.fromFile(f));st.importJson(json,Integer.MAX_VALUE);}
        @Override public File assetFile(String name){
            if(name==null)return null;if(name.endsWith(".png")){File d=new File(getFilesDir(),"images");d.mkdirs();return new File(d,name);}
            if(name.endsWith(".m4a"))return new File(recordingsDir(),name);
            if(name.endsWith(".mp4")){File d=new File(getFilesDir(),"videos");d.mkdirs();return new File(d,name);}return null;}
        @Override public void onActivity(String message){runOnUiThread(()->toast(message));}
    };
    private void showDeviceSync(){
        if(deviceSync!=null)return;
        final DeviceSync sync=new DeviceSync(syncHost);final String address=DeviceSync.localAddress();
        try{sync.start();}catch(IOException error){toast("동기화를 시작할 수 없습니다: "+error.getMessage());return;}
        deviceSync=sync;getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String where=address==null?"(Wi-Fi에 연결되어 있지 않습니다)":address+":"+sync.port();
        new AlertDialog.Builder(this).setTitle("다른 기기와 동기화")
            .setMessage("같은 Wi-Fi에 연결된 PC 앱(Everynote for Windows)의 ‘기기 동기화’에서 아래 주소와 코드를 입력하세요. 보낼 문서와 방향은 PC 앱에서 고릅니다.\n\n주소  "+where+"\n코드  "+sync.code()+"\n\n· 선택한 문서의 PDF·노트·이미지·녹음이 통째로 덮어써집니다.\n· 이 앱에서 열려 있는 문서는 덮어쓸 수 없으니 닫아 두세요.\n· 이 창을 닫으면 동기화가 끝납니다.")
            .setPositiveButton("닫기",null)
            .setOnDismissListener(d->{sync.stop();if(deviceSync==sync)deviceSync=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}).show();
    }
    private File recordingsDir(){File dir=new File(getFilesDir(),"recordings");dir.mkdirs();return dir;}
    private void startRecording(){
        if(renderer==null||store==null){toast("문서를 먼저 여세요");return;}
        if(recorder!=null){toast("이미 녹음 중입니다");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},REQUEST_RECORD);return;}
        File file=new File(recordingsDir(),UUID.randomUUID()+".m4a");MediaRecorder r=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
        try{r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioEncodingBitRate(96000);r.setAudioSamplingRate(44100);r.setOutputFile(file.getAbsolutePath());r.prepare();r.start();}
        catch(Exception error){try{r.release();}catch(RuntimeException ignored){}file.delete();toast("녹음을 시작할 수 없습니다");return;}
        recorder=r;recordingFile=file;recordingStore=store;recordingPage=currentPage;recordingStarted=SystemClock.elapsedRealtime();showRecorderBar();if(sidebarVisible&&panelTab==3)rebuildRecordings();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==REQUEST_RECORD){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)startRecording();else toast("마이크 권한이 있어야 녹음할 수 있습니다");}}
    private void showRecorderBar(){
        recorderBar=new LinearLayout(this);recorderBar.setTag("recorder_bar");recorderBar.setGravity(Gravity.CENTER_VERTICAL);recorderBar.setPadding(dp(16),dp(6),dp(8),dp(6));recorderBar.setBackground(round(0xFAFFFFFF,28));recorderBar.setElevation(dp(8));
        TextView dot=new TextView(this);dot.setText("●");dot.setTextColor(0xFFFF3B30);dot.setTextSize(16);recorderBar.addView(dot);
        recorderTime=new TextView(this);recorderTime.setText("녹음 중 0:00");recorderTime.setTextSize(15);recorderTime.setTextColor(NAVY);recorderTime.setTypeface(Typeface.DEFAULT_BOLD);recorderTime.setPadding(dp(8),0,dp(12),0);recorderBar.addView(recorderTime);
        TextView stop=pill("정지·첨부","녹음 정지 후 첨부",0xFFFF3B30,Color.WHITE,v->stopRecording(true));stop.setTag("recorder_stop");stop.setPadding(dp(14),0,dp(14),0);recorderBar.addView(stop,new LinearLayout.LayoutParams(-2,dp(40)));
        TextView cancel=pill("취소","녹음 취소",0xFFF2F2F7,NAVY,v->stopRecording(false));cancel.setTag("recorder_cancel");cancel.setPadding(dp(14),0,dp(14),0);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(40));cp.setMargins(dp(6),0,0,0);recorderBar.addView(cancel,cp);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.setMargins(dp(8),0,dp(8),dp(14));viewportLayer.addView(recorderBar,lp);
        recorderBar.post(new Runnable(){public void run(){if(recorder==null||recorderBar==null)return;recorderTime.setText("녹음 중 "+clock((SystemClock.elapsedRealtime()-recordingStarted)/1000));recorderBar.postDelayed(this,500);}});
    }
    private void stopRecording(boolean attach){
        MediaRecorder r=recorder;if(r==null)return;recorder=null;boolean ok=true;long seconds=(SystemClock.elapsedRealtime()-recordingStarted)/1000;
        try{r.stop();}catch(RuntimeException error){ok=false;}try{r.release();}catch(RuntimeException ignored){}
        if(recorderBar!=null){viewportLayer.removeView(recorderBar);recorderBar=null;}
        File file=recordingFile;AnnotationStore target=recordingStore;recordingFile=null;recordingStore=null;
        if(!attach||!ok||file==null||!file.isFile()||target==null){if(file!=null)file.delete();if(attach&&!ok)toast("녹음이 너무 짧아 저장하지 않았습니다");if(sidebarVisible&&panelTab==3)rebuildRecordings();return;}
        int count=0;for(AnnotationStore.PageElement other:target.elements)if(other.page==recordingPage&&"audio".equals(other.kind))count++;
        AnnotationStore.PageElement clip=new AnnotationStore.PageElement();clip.page=recordingPage;clip.kind="audio";clip.asset=file.getName();clip.text=clock(seconds);clip.left=.04f;clip.top=Math.min(.9f,.03f+.055f*(count%16));clip.right=.42f;clip.bottom=clip.top+.045f;
        target.elements.add(clip);target.save();redrawPages();if(sidebarVisible&&panelTab==3)rebuildRecordings();toast("녹음을 p."+(recordingPage+1)+"에 첨부했습니다");
    }
    private void showRecordingMenu(AnnotationStore.PageElement clip,View anchor){AnchoredMenu.showRightOf(this,anchor,sidePanel,AnchoredMenu.rows(
        new AnchoredMenu.Row("재생",R.drawable.ic_speaker,()->{showPage(clip.page);showAudioPlayer(clip);}).tint(0xFF30B0C7),
        new AnchoredMenu.Row("삭제",R.drawable.ic_delete,()->deleteRecording(clip)).danger()));}
    private void deleteRecording(AnnotationStore.PageElement clip){if(store==null)return;store.elements.remove(clip);store.save();new File(recordingsDir(),clip.asset).delete();redrawPages();if(sidebarVisible&&panelTab==3)rebuildRecordings();}
    private void showAudioPlayer(AnnotationStore.PageElement clip){
        File file=new File(recordingsDir(),clip.asset);
        if(!file.isFile()){new AlertDialog.Builder(this).setTitle("녹음").setMessage("녹음 파일을 찾을 수 없습니다. 이 기기에서 만든 녹음만 재생할 수 있습니다.").setPositiveButton("닫기",null).setNegativeButton("삭제",(d,w)->deleteRecording(clip)).show();return;}
        final MediaPlayer player=new MediaPlayer();
        try{player.setDataSource(file.getAbsolutePath());player.prepare();}catch(Exception error){try{player.release();}catch(RuntimeException ignored){}toast("녹음을 재생할 수 없습니다");return;}
        final int total=player.getDuration();final boolean[] alive={true};final Handler handler=new Handler(Looper.getMainLooper());
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),dp(8),dp(16),dp(4));
        final TextView time=new TextView(this);time.setTag("audio_time");time.setTextSize(15);time.setTextColor(NAVY);time.setGravity(Gravity.CENTER);time.setText(clock(0)+" / "+clock(total/1000));panel.addView(time,new LinearLayout.LayoutParams(-1,dp(32)));
        final SeekBar seek=new SeekBar(this);seek.setMax(Math.max(1,total));panel.addView(seek,new LinearLayout.LayoutParams(-1,dp(36)));
        final TextView play=pill("▶ 재생","녹음 재생",ACTIVE_BG,ACTIVE_FG,null);play.setTag("audio_play");LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(46));pp.topMargin=dp(6);panel.addView(play,pp);
        final Runnable[] tick=new Runnable[1];
        tick[0]=()->{if(!alive[0])return;try{int position=player.getCurrentPosition();seek.setProgress(position);time.setText(clock(position/1000)+" / "+clock(total/1000));if(player.isPlaying())handler.postDelayed(tick[0],250);else play.setText("▶ 재생");}catch(IllegalStateException ignored){}};
        play.setOnClickListener(v->{if(!alive[0])return;try{if(player.isPlaying()){player.pause();play.setText("▶ 재생");}else{if(player.getCurrentPosition()>=total-100)player.seekTo(0);player.start();play.setText("❚❚ 일시정지");handler.post(tick[0]);}}catch(IllegalStateException ignored){}});
        player.setOnCompletionListener(mp->{play.setText("▶ 재생");seek.setProgress(total);});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser&&alive[0])try{player.seekTo(progress);}catch(IllegalStateException ignored){}}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("녹음 · p."+(clip.page+1)).setView(panel).setPositiveButton("닫기",null).setNegativeButton("삭제",null).create();
        dialog.setOnDismissListener(d->{alive[0]=false;handler.removeCallbacksAndMessages(null);try{player.release();}catch(RuntimeException ignored){}});
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{dialog.dismiss();deleteRecording(clip);}));
        dialog.show();
    }

    // ================================================================== docked search
    private void buildSearchPanel(){
        searchPanel=new LinearLayout(this);searchPanel.setTag("search_panel");searchPanel.setOrientation(LinearLayout.VERTICAL);searchPanel.setBackgroundColor(0xFFF8F8F8);searchPanel.setVisibility(View.GONE);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(6),dp(4),dp(2));
        searchInput=new EditText(this);searchInput.setTag("search_input");searchInput.setHint("검색어를 입력하고 Enter");searchInput.setSingleLine();searchInput.setTextSize(15);searchInput.setTextColor(NAVY);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);searchInput.setBackground(round(0xFFF2F2F7,20));searchInput.setPadding(dp(14),0,dp(14),0);
        searchInput.setOnEditorActionListener((v,action,event)->{
            boolean enter=event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER;
            if(action==EditorInfo.IME_ACTION_SEARCH||action==EditorInfo.IME_ACTION_DONE||action==EditorInfo.IME_ACTION_GO||enter){if(!enter||event.getAction()==KeyEvent.ACTION_DOWN)startSearch();return true;}
            return false;
        });
        searchRow=row;searchNav=new LinearLayout(this);searchNav.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        row.addView(searchInput,new LinearLayout.LayoutParams(0,dp(40),1));
        searchNav.addView(icon(R.drawable.ic_chevron_up,"이전 결과",NAVY,v->stepSearch(-1)),new LinearLayout.LayoutParams(dp(40),dp(40)));
        searchNav.addView(icon(R.drawable.ic_chevron_down,"다음 결과",NAVY,v->stepSearch(1)),new LinearLayout.LayoutParams(dp(40),dp(40)));
        searchNav.addView(icon(R.drawable.ic_close,"검색 닫기",0xFF8E8E93,v->closeSearch()),new LinearLayout.LayoutParams(dp(40),dp(40)));
        row.addView(searchNav,new LinearLayout.LayoutParams(-2,dp(40)));
        searchPanel.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout info=new LinearLayout(this);info.setGravity(Gravity.CENTER_VERTICAL);info.setPadding(dp(16),0,dp(10),dp(4));
        searchStatus=new TextView(this);searchStatus.setTag("search_status");searchStatus.setTextSize(12);searchStatus.setTextColor(0xFF8E8E93);searchStatus.setSingleLine();searchStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(searchStatus,new LinearLayout.LayoutParams(0,dp(28),1));
        TextView precise=new TextView(this);precise.setTag("search_precise");precise.setText("정밀(OCR)");precise.setTextSize(12);precise.setGravity(Gravity.CENTER);precise.setPadding(dp(10),0,dp(10),0);precise.setContentDescription("스캔·손글씨까지 글자 인식으로 정밀 검색");
        Runnable paint=()->{precise.setBackground(round(searchPrecise?ACCENT:0xFFF2F2F7,14));precise.setTextColor(searchPrecise?Color.WHITE:NAVY);};paint.run();
        precise.setOnClickListener(v->{searchPrecise=!searchPrecise;paint.run();if(searchInput.getText().toString().trim().length()>0)startSearch();});
        info.addView(precise,new LinearLayout.LayoutParams(-2,dp(28)));
        searchPanel.addView(info,new LinearLayout.LayoutParams(-1,-2));
        searchList=new LinearLayout(this);searchList.setOrientation(LinearLayout.VERTICAL);
        searchScroll=new ScrollView(this);searchScroll.setVisibility(View.GONE);searchScroll.addView(searchList,new ScrollView.LayoutParams(-1,-2));
        searchPanel.addView(searchScroll,new LinearLayout.LayoutParams(-1,0,1));
        View divider=new View(this);divider.setBackgroundColor(0xFFE5E5EA);searchPanel.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        updateSearchStatus();
    }
    private void searchDocument(){
        if(activeSession==null){toast("문서를 먼저 여세요");return;}
        selectPanelTab(0);searchInput.selectAll();
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT);
    }
    private void hideKeyboard(){InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null&&searchInput!=null)keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(),0);}
    private void startSearch(){
        if(activeSession==null)return;
        final String query=searchInput.getText().toString().trim();if(query.isEmpty()){toast("검색어를 입력하세요");return;}
        hideKeyboard();
        final DocumentSession session=activeSession;final Uri source=session.officePreview==null?session.uri:Uri.fromFile(session.officePreview);
        final String json;try{json=session.store.exportJson(session.uri,session.title);}catch(JSONException error){return;}
        final int count=session.renderer.getPageCount();final int start=Math.max(0,Math.min(count-1,currentPage));final boolean precise=searchPrecise;
        if(searchCanceled!=null)searchCanceled.set(true);
        final java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean();searchCanceled=cancel;
        final int token=++searchSession;searchOwner=session;
        searchHits.clear();searchCurrent=-1;searchList.removeAllViews();searchScroll.setVisibility(View.GONE);searching=true;searchDone=0;searchTotal=count;searchTruncated=false;
        applySearchHighlights();updateSearchStatus();
        new Thread(()->{
            try{
                AnnotationStore snapshot=new AnnotationStore(this);snapshot.importJson(json,count);
                boolean truncated=SearchScanner.scan(this,source,snapshot,count,start,query,precise,cancel,new SearchScanner.Listener(){
                    @Override public void progress(int done,int total){runOnUiThread(()->{if(token!=searchSession)return;searchDone=done;searchTotal=total;updateSearchStatus();});}
                    @Override public void hits(List<SearchScanner.Hit> hits){final List<SearchScanner.Hit> batch=new ArrayList<>(hits);runOnUiThread(()->{if(token==searchSession)addSearchHits(batch);});}
                });
                runOnUiThread(()->{if(token!=searchSession)return;searching=false;searchTruncated=truncated;updateSearchStatus();});
            }catch(Exception error){
                final String reason=String.valueOf(error.getMessage());
                runOnUiThread(()->{if(token!=searchSession)return;searching=false;updateSearchStatus();if(!cancel.get())toast("검색 실패: "+reason);});
            }
        },"document-search").start();
    }
    private void addSearchHits(List<SearchScanner.Hit> hits){
        boolean first=searchHits.isEmpty();
        for(SearchScanner.Hit hit:hits){int index=searchHits.size();searchHits.add(hit);searchList.addView(hitRow(index,hit));}
        searchScroll.setVisibility(searchHits.isEmpty()?View.GONE:View.VISIBLE);
        if(first&&!searchHits.isEmpty())selectHit(0,false);else{applySearchHighlights();updateSearchStatus();}
    }
    private View hitRow(int index,SearchScanner.Hit hit){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setTag("search_hit_"+index);row.setPadding(dp(16),dp(8),dp(12),dp(8));row.setContentDescription("검색 결과 "+(index+1));
        TextView meta=new TextView(this);meta.setText("p."+(hit.page+1)+" · "+hit.kind);meta.setTextSize(11);meta.setTextColor(ACCENT);meta.setTypeface(Typeface.DEFAULT_BOLD);row.addView(meta);
        SpannableString snippet=new SpannableString(hit.text==null?"":hit.text);
        if(hit.matchStart>=0&&hit.matchEnd>hit.matchStart&&hit.matchEnd<=snippet.length()){snippet.setSpan(new BackgroundColorSpan(0x66FFDE59),hit.matchStart,hit.matchEnd,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);snippet.setSpan(new StyleSpan(Typeface.BOLD),hit.matchStart,hit.matchEnd,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);}
        TextView text=new TextView(this);text.setText(snippet);text.setTextSize(14);text.setTextColor(NAVY);text.setMaxLines(2);text.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(text);
        row.setOnClickListener(v->{hideKeyboard();selectHit(index,true);});
        return row;
    }
    /** Jumps to one result without touching the result list, so the others stay one tap away. */
    private void selectHit(int index,boolean scrollToRow){
        if(index<0||index>=searchHits.size()||activeSession==null)return;
        searchCurrent=index;final SearchScanner.Hit hit=searchHits.get(index);
        if(hit.page!=currentPage||viewForPage(hit.page)==null)showPage(hit.page);
        applySearchHighlights();updateSearchStatus();
        for(int i=0;i<searchList.getChildCount();i++){View row=searchList.getChildAt(i);boolean on=i==index;row.setBackgroundColor(on?0xFFE5E5EA:Color.TRANSPARENT);}
        final View row=searchList.getChildAt(index);if(scrollToRow&&row!=null)row.post(()->searchScroll.smoothScrollTo(0,Math.max(0,row.getTop()-dp(8))));
        final PdfPageView target=viewForPage(hit.page);if(target!=null)target.post(()->target.focusOnPoint(hit.x,hit.y));
    }
    private void stepSearch(int direction){
        if(searchHits.isEmpty()){if(searchInput.getText().toString().trim().length()>0&&!searching)startSearch();return;}
        int next=searchCurrent<0?(direction>0?0:searchHits.size()-1):(searchCurrent+direction+searchHits.size())%searchHits.size();selectHit(next,true);
    }
    private void applySearchHighlights(){
        for(PdfPageView view:paneViews){
            if(view==null)continue;
            if(searchHits.isEmpty()||searchPanel==null||searchPanel.getVisibility()!=View.VISIBLE){view.clearSearchHighlights();continue;}
            List<RectF> others=new ArrayList<>();RectF current=null;
            for(int i=0;i<searchHits.size();i++){SearchScanner.Hit hit=searchHits.get(i);if(hit.page!=view.getPageNumber())continue;if(i==searchCurrent)current=hit.box;else others.add(hit.box);}
            view.setSearchHighlights(view.getPageNumber(),others,current);
        }
    }
    private void updateSearchStatus(){
        if(searchStatus==null)return;String text;
        if(searching)text="검색 중 "+searchDone+"/"+searchTotal+"쪽 · 결과 "+searchHits.size()+"개";
        else if(searchHits.isEmpty())text=searchOwner==null?"Enter를 누르면 본문·필기·메모를 검색합니다":"결과가 없습니다"+(searchPrecise?"":" · 손글씨·스캔은 ‘정밀(OCR)’로 다시 찾아보세요");
        else text="결과 "+searchHits.size()+"개"+(searchTruncated?"+ (상위 "+SearchScanner.MAX_HITS+"개만 표시)":"")+(searchCurrent>=0?" · "+(searchCurrent+1)+"번째":"");
        searchStatus.setText(text);
    }
    private void closeSearch(){
        if(searchCanceled!=null)searchCanceled.set(true);++searchSession;searching=false;searchOwner=null;
        searchHits.clear();searchCurrent=-1;if(searchList!=null)searchList.removeAllViews();if(searchScroll!=null)searchScroll.setVisibility(View.GONE);
        if(searchPanel!=null){hideKeyboard();searchPanel.setVisibility(View.GONE);if(sidebarVisible&&panelTab==0)selectPanelTab(1);}
        updateSearchStatus();applySearchHighlights();
    }

    // ================================================================== favorites-only page previews
    private View thumbnailModeToggle(){
        LinearLayout row=new LinearLayout(this);row.setTag("thumb_toggle");row.setPadding(0,0,0,dp(6));
        String[] labels={"★ 즐겨찾기","전체","필기"};String[] descriptions={"즐겨찾기한 페이지만 미리보기","전체 페이지 미리보기","필기가 있는 페이지만 미리보기"};
        int current=thumbInk?2:showAllThumbnails?1:0;
        for(int i=0;i<3;i++){
            final int mode=i;boolean on=mode==current;
            TextView chip=new TextView(this);chip.setText(labels[i]);chip.setTextSize(10);chip.setSingleLine();chip.setGravity(Gravity.CENTER);chip.setBackground(round(on?ACCENT:0xFFE5E5EA,14));chip.setTextColor(on?Color.WHITE:NAVY);chip.setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
            chip.setContentDescription(descriptions[i]);
            chip.setOnClickListener(v->{if(mode==(thumbInk?2:showAllThumbnails?1:0))return;setThumbMode(mode);rebuildThumbnails();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(30),mode==0?3f:2f);p.setMargins(dp(2),0,dp(2),0);row.addView(chip,p);
        }
        return row;
    }
    private List<Integer> thumbnailPages(){
        List<Integer> pages=new ArrayList<>();int total=renderer==null?0:renderer.getPageCount();
        if(thumbInk){if(store!=null){Set<Integer> written=new HashSet<>();for(AnnotationStore.InkStroke stroke:store.strokes)written.add(stroke.page);for(int i=0;i<total;i++)if(written.contains(i))pages.add(i);}}
        else if(showAllThumbnails){for(int i=0;i<total;i++)pages.add(i);}
        else if(store!=null){for(Integer page:store.bookmarks)if(page!=null&&page>=0&&page<total)pages.add(page);Collections.sort(pages);}
        return pages;
    }
    private void rebuildThumbnails(){
        if(!sidebarVisible)return;if(panelTab==2){rebuildOutlinePanel();return;}if(panelTab==3){rebuildRecordings();return;}if(panelTab==4){rebuildInsertions();return;}if(panelTab!=1)return;final int generation=++thumbnailGeneration;thumbnailList.removeAllViews();if(renderer==null)return;
        final List<Integer> pages=thumbnailPages();
        if(pages.isEmpty()){
            TextView empty=new TextView(this);empty.setTag("thumb_empty");empty.setText(thumbInk?"필기한 페이지가 없습니다.\n\n펜이나 형광펜으로 쓰면\n이곳에 미리보기가 나타납니다.":"즐겨찾기한 페이지가 없습니다.\n\n아래쪽 ★를 누르면\n이곳에 미리보기가 나타납니다.");empty.setTextSize(12);empty.setTextColor(0xFF8E8E93);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(4),dp(18),dp(4),dp(8));
            thumbnailList.addView(empty,new LinearLayout.LayoutParams(-1,-2));return;
        }
        for(int i=0;i<pages.size();i++){final int page=pages.get(i);LinearLayout item=new LinearLayout(this);item.setTag(page);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setPadding(dp(6),dp(8),dp(6),dp(10));ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setAdjustViewBounds(true);preview.setBackgroundColor(Color.WHITE);preview.setElevation(dp(1));item.addView(preview,new LinearLayout.LayoutParams(sidePanelWidth()-dp(60),Math.round((sidePanelWidth()-dp(60))*thumbnailAspect())));TextView number=new TextView(this);number.setText(String.valueOf(page+1));number.setGravity(Gravity.CENTER);number.setTextSize(12);number.setTextColor(0xFF8E8E93);LinearLayout numberRow=new LinearLayout(this);numberRow.setGravity(Gravity.CENTER_VERTICAL);numberRow.addView(number,new LinearLayout.LayoutParams(0,dp(24),1));TextView pageMore=new TextView(this);pageMore.setText("⋮");pageMore.setTextSize(15);pageMore.setTextColor(0xFF8E8E93);pageMore.setGravity(Gravity.CENTER);pageMore.setContentDescription("페이지 "+(page+1)+" 메뉴");pageMore.setOnClickListener(v->showPageMenu(page,v));numberRow.addView(pageMore,new LinearLayout.LayoutParams(dp(28),dp(24)));item.addView(numberRow,new LinearLayout.LayoutParams(-1,dp(24)));item.setOnClickListener(v->showPage(page));item.setOnLongClickListener(v->{showPageMenu(page,v);return true;});thumbnailList.addView(item,new LinearLayout.LayoutParams(-1,-2));}
        updateThumbnailSelection();renderThumbnail(pages,0,generation,activeSession);
    }
    /** Height/width of the document's first page, used to size thumbnail placeholders so the whole page always fits. */
    private float thumbnailAspect(){if(renderer==null||renderer.getPageCount()==0)return 1.41f;try(PdfRenderer.Page page=renderer.openPage(0)){return Math.max(.5f,Math.min(2.2f,page.getHeight()/(float)Math.max(1,page.getWidth())));}catch(Exception error){return 1.41f;}}
    private void renderThumbnail(List<Integer> pages,int position,int generation,DocumentSession session){
        if(generation!=thumbnailGeneration||session!=activeSession||renderer==null||position>=pages.size())return;
        thumbnailList.post(()->{
            if(generation!=thumbnailGeneration||session!=activeSession||renderer==null)return;
            final int index=pages.get(position);
            try(PdfRenderer.Page page=renderer.openPage(index)){int width=300;float ratio=(float)width/page.getWidth();Bitmap image=Bitmap.createBitmap(width,Math.max(1,(int)(page.getHeight()*ratio)),Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);Matrix matrix=new Matrix();matrix.postScale(ratio,ratio);page.render(image,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);AnnotationPainter.all(this,new Canvas(image),new RectF(0,0,image.getWidth(),image.getHeight()),session.store,index);View item=thumbnailList.findViewWithTag(index);if(item instanceof LinearLayout){View child=((LinearLayout)item).getChildAt(0);if(child instanceof ImageView)((ImageView)child).setImageBitmap(image);}}catch(Exception ignored){}
            renderThumbnail(pages,position+1,generation,session);
        });
    }

    private static final String[][] HELP={
        {"1. 기본 원리",
         "문서 위에 겹쳐 쓰기|필기·하이라이트·메모·도형·사진 같은 모든 기록은 PDF 위에 얹는 별도 ‘주석 층’으로 앱 안에 저장됩니다. 원본 PDF 파일은 바뀌지 않으며, 내보내기를 하면 기록이 합쳐진 새 PDF가 만들어집니다.",
         "자동 저장|기록은 바로 저장되고, 앱을 다시 열면 열어 둔 탭과 마지막 페이지가 복원됩니다.",
         "문서함|상단 왼쪽 폴더 버튼에서 PDF·노트·Office 문서를 폴더별로 관리합니다. 새 문서는 폴더 버튼 또는 탭 줄의 + 버튼으로 추가합니다. 문서를 길게 누르면 선택 모드가 되어 위쪽 줄에서 이동·공유·삭제·전체 선택을 하고 ⋮에서 이름 변경·복사본 만들기·즐겨찾기를 합니다. 설정은 문서함의 설정 항목(더보기 메뉴 포함)에서 엽니다.",
         "여러 문서|상단 탭으로 문서를 전환하고 × 로 닫습니다. 기록은 문서마다 따로 저장됩니다."},
        {"2. 화면 구성",
         "상단 줄|문서함, 문서 이름, 페이지 미리보기, 검색, 전체 화면, 더보기(⋮) 메뉴가 있습니다.",
         "하단 도구 줄|읽기 · 펜 · 형광펜 · 지우개 · 올가미 · 텍스트 · 메모 · 삽입 · 실행 취소 · 다시 실행 순서입니다. 선택한 도구는 배경이 진하게 표시되고, 선택된 도구를 한 번 더 누르면 굵기·색 같은 세부 설정이 열립니다.",
         "왼쪽 패널|검색 · 페이지 미리보기 · 개요 · 음성 녹음 탭이 있습니다. 패널과 문서 사이의 회색 막대를 좌우로 끌면 폭을 조절하고, 조절한 폭은 기억됩니다.",
         "반투명 화살표|본문 양옆의 화살표를 누르면 이전·다음 페이지로 이동합니다."},
        {"3. 읽기와 이동",
         "페이지 넘기기|본문을 좌우로 쓸어 넘깁니다. 손가락을 따라 책장이 접히며, 아래쪽을 잡으면 아래 모서리부터, 위쪽을 잡으면 위쪽부터 넘어갑니다. 마우스를 연결했다면 휠을 위·아래로 굴려 이전·다음 페이지로 넘길 수 있습니다. 넘김 효과는 도구 메뉴의 ‘넘김 효과’에서 바꿉니다.",
         "확대·이동|두 손가락으로 확대하거나, 화면 왼쪽 아래의 ＋ / － 버튼으로 25%씩 확대·축소합니다(가운데 % 숫자를 누르면 100%). 마우스는 Ctrl + 휠로 확대합니다. 확대한 상태에서는 드래그나 휠로 화면을 옮기고, 확대 중에는 화면 가장자리에서 쓸어야 페이지가 넘어갑니다.",
         "전체 화면|상단의 전체 화면 버튼을 누르면 메뉴가 숨겨집니다. 화면 아래에서 위로 쓸어올리면 도구 모음이 다시 나타납니다.",
         "검은 문서 배경|더보기 메뉴에서 켜면 종이를 검게, 글자는 밝게 표시합니다. 어두운 색 필기는 자동으로 밝게 보정됩니다.",
         "두 쪽 보기|가로로 넓은 화면(태블릿·폴드)에서 두 페이지를 나란히 봅니다."},
        {"4. 텍스트 선택과 단어 찾기",
         "선택하기|단어를 길게 누른 뒤 드래그해서 범위를 정합니다.",
         "선택 팝업|빈 곳을 길게 눌렀을 때와 같은 모양의 메뉴가 선택한 글자를 가리지 않는 위치(아래·위·옆)에 열립니다. 하이라이트 · 복사 · 번역 · 읽어주기 · 단어장 · 개요 · 메모 · 발췌 · 링크가 있고, 맨 아래 ‘삽입’을 누르면 사진·스티커·도형 같은 삽입 항목이 열립니다.",
         "단어장 연결|‘단어장’을 누르면 단어가 복사되고 영어 스터디 앱(github.com/hdlee73/english_study)이 열려 사전 탭에서 자동으로 검색됩니다. 최신 영어 스터디 앱은 사전 탭으로 바로 이동해 검색하고 위쪽의 ‘Everynote로 돌아가기’ 줄로 돌아옵니다(이전 버전은 사전 탭이 열려 있을 때만 복사된 단어를 검색). 앱이 없으면 설치 안내가 나옵니다. 돌아올 때는 위 줄이나 최근 앱 화면, 뒤로 가기를 사용합니다."},
        {"5. 필기 (펜)",
         "펜 선택|하단의 연필 아이콘을 눌러 필기 모드로 들어갑니다. S펜은 바로 쓰이고, 손가락 필기는 펜 메뉴의 ‘손가락 필기’를 켜야 합니다.",
         "펜 종류|펜 메뉴 맨 위 두 줄은 아이콘입니다. 첫 줄은 굵기(4단계, 선이 굵어지는 순서), 둘째 줄은 볼펜 · 연필 · 만년필 · 붓 · 사인펜이며 각 칸에 실제 획 모양이 그려져 있습니다. 연필은 살짝 투명하고 만년필은 속도에 따라 굵기가 변하며 붓은 끝이 뾰족하게 가늘어집니다.",
         "굵기·색·투명도|색은 기본 팔레트 또는 무지개 칩으로 직접 고르고, 투명도 막대로 농도를 정합니다. 하이라이트와 도형·메모 색도 같은 방식입니다.",
         "직선|펜 메뉴의 ‘직선’을 켜면 시작점과 끝점을 잇는 반듯한 선을 긋습니다.",
         "지우개|지우개 아이콘으로 필기와 하이라이트를 지웁니다. 지울 부분을 문지르거나 눌러서 한 획(하이라이트는 한 덩어리)씩 지워집니다. 실행 취소·다시 실행도 사용할 수 있습니다.",
         "올가미|영역을 그려 캡처하거나 글자를 복사합니다. 올가미 단추를 한 번 더 누르면 모양(자유·네모·원) 메뉴가 떠서 고를 수 있고, 영역을 그리면 복사·저장·공유 메뉴가 열립니다."},
        {"6. 형광펜과 하이라이트",
         "형광펜|필기 도구 줄의 형광펜은 펜처럼 쓰는 필기의 한 종류입니다. 쓰듯이 자유롭게 칠할 수 있고, 한 번 더 누르면 색·투명도·굵기와 모양(직선 / 자유형)을 고릅니다. 지우개로 지우고 실행 취소도 됩니다.",
         "하이라이트|글자를 따라 반듯하게 칠하는 도구입니다. 삽입 메뉴의 ‘하이라이트’를 켜고 글자를 좌우로 끌거나, 글자를 선택한 뒤 팝업의 ‘하이라이트’를 누릅니다. 굵기만 조절할 수 있고, 하이라이트를 누르면 메모를 달 수 있습니다.",
         "삽입 목록|하이라이트와 메모는 모두 ‘삽입 목록’ 탭의 ‘메모·하이라이트’에 모여, 누르면 그 위치로 이동합니다. 하이라이트는 개요 목록에는 나타나지 않습니다."},
        {"7. 텍스트 상자",
         "넣기|읽기 모드 하단의 필기 아이콘 옆 T 아이콘을 누르고 문서를 탭하면 입력할 수 있습니다. 입력 중에는 한 줄짜리 서식 막대가 글상자 위에 붙고, ‘Aa’를 누르면 정렬(왼쪽·가운데·오른쪽) · 밑줄 · 취소선 · 글머리 기호 · 번호 매기기 · 체크리스트와 글꼴 · 색이 펼쳐집니다. 목록 줄에서 Enter를 누르면 다음 항목이 이어지고, 빈 항목에서 Enter를 누르면 목록이 끝납니다. 체크리스트 버튼을 한 번 더 누르면 ☑ 로 바뀌고, 또 누르면 해제됩니다. 키보드가 올라와도 페이지 크기는 줄지 않습니다.",
         "이동·크기·삭제|입력 중 상자 위의 핸들로 이동하고, 모서리 핸들로 너비를 조절하며, 빨간 휴지통 또는 상자 위의 × 로 삭제합니다. ✓ 버튼으로 입력을 마칩니다."},
        {"8. 메모 포스트잇",
         "만들기|삽입 메뉴의 ‘메모’를 누르고 문서를 탭해 내용을 입력합니다. 글자를 선택한 뒤 팝업의 ‘메모’로도 만들 수 있습니다.",
         "크기 조절|메모를 한 번 탭하면 점선 테두리와 오른쪽 아래 둥근 핸들이 나타납니다. 핸들을 끌어 가로·세로 크기를 자유롭게 바꿉니다. 이미 선택된 메모를 다시 탭하면 편집 창이 열립니다.",
         "편집 창|글자 크기(− ＋)는 메모 안 글자의 크기이고, 메모 상자 크기(작게·보통·크게)는 상자의 기본 크기입니다. 상자 크기를 고르면 직접 조절한 크기는 초기화됩니다. 메모 색은 기본 색, 무지개 칩, 투명도로 정합니다.",
         "숨기기·최소화|메모와 번역 포스트잇은 펼치기 · 최소화 · 숨기기로 관리합니다."},
        {"9. 삽입: 사진 · 스티커 · 도형 · 표 · 링크",
         "삽입 메뉴|하단의 + 상자 아이콘을 누르거나 문서의 빈 곳을 길게 눌러 열고, 넣을 종류를 고릅니다. 사진·동영상·유튜브 주소는 끌어다 놓거나 붙여넣기(Ctrl+V)도 됩니다.",
         "선택·이동·크기|넣은 개체(사진·스티커·도형·표 등)를 한 번 탭하면 테두리와 핸들이 보입니다. 몸통을 끌면 이동, 네 모서리 핸들을 끌면 가로세로 비율을 유지한 채 크기 조절, 사진·스티커·동영상은 변 가운데의 막대 핸들을 끌면 가로 또는 세로만 따로 늘이거나 줄일 수 있습니다. 오른쪽 위의 빨간 × 를 누르면 바로 삭제되고, 한 번 더 탭하면 편집 창이 열립니다.",
         "회전|사진·스티커·도형·표는 선택하면 위쪽에 ↻ 핸들이 나타납니다. 끌면 돌아가고 15° 단위 근처에서 자석처럼 맞춰집니다. 도형은 모양 수정 창의 ‘회전’ 막대로 각도를 정할 수도 있습니다.",
         "도형·표|선 색, 채우기 색, 선 굵기를 정하고, 색마다 무지개 칩으로 원하는 색과 투명도를 고릅니다. 표는 행·열 수, 머리글 색, 칸 내용을 편집할 수 있습니다.",
         "하이퍼링크|글자를 선택한 뒤 팝업의 ‘링크’를 눌러 웹 주소, 현재 문서의 다른 페이지, 다른 문서로 연결합니다. 링크 글자는 파란 밑줄과 작은 화살표 배지로 표시되고, 탭하면 이동합니다."},
        {"10. 개요와 북마크",
         "개요 추가|개요 패널의 ‘＋ 개요 추가’를 누르고 문서의 원하는 위치를 탭한 뒤 제목을 입력합니다. 목록에는 ‘제목 (p19)’ 형식으로 표시되고 탭하면 그 위치로 이동합니다.",
         "관리|목록을 옆으로 밀면 삭제되고, ⋮ 버튼으로 이름 변경·삭제를 할 수 있습니다. 즐겨찾기(별)는 현재 페이지를 표시합니다."},
        {"11. 새 노트와 서식",
         "새 노트|문서함에서 새 노트를 만들면 종이 서식을 고릅니다. 맨 위에 금감원노트·금감원노트_칸나누기·리갈노트 서식이 있고 기본값은 금감원노트입니다. 백지 · 줄노트(보통·좁게·넓게) · 모눈종이 · 리걸노트 · 점 격자 · 코넬 노트 · 오선지가 있고, 종이 색도 고를 수 있습니다. 종이는 미리보기 칸에서 바로 고르고, 생성되는 노트는 세로 또는 가로로 만들 수 있습니다. 설정 > 기본 노트 스타일에서 정해 둔 종이·색·방향이 새 노트를 만들 때 가장 먼저 선택되어 있습니다.",
         "내 서식|‘내 PDF·이미지 서식’을 고르면 가지고 있는 PDF의 첫 페이지나 이미지를 모든 페이지의 배경으로 씁니다.",
         "페이지 추가|‘페이지 추가’는 바로 앞 페이지와 같은 크기·방향·서식(백지, 금감원노트 등)의 새 페이지를 붙입니다. 노트의 마지막 장에서 다음으로 넘겨도 같은 페이지가 붙습니다. ‘다른 형식으로 페이지 추가’에서는 다른 종이와 A4 세로·가로 크기를 고를 수 있습니다."},
        {"12. 음성 녹음 · 검색 · 번역",
         "음성 녹음|개요 패널의 마이크 탭에서 녹음하면 현재 페이지에 ‘▶ 녹음’ 표시가 붙고, 탭하면 재생합니다.",
         "검색|돋보기 아이콘으로 본문 글자를 찾고, 손글씨 필기도 검색됩니다.",
         "번역·읽어주기|글자를 선택한 뒤 팝업에서 번역을 누르면 기기 안에서 바로 번역해(처음 한 번만 번역 모델을 내려받습니다) 원문과 번역을 보여 주는 창이 열립니다. 창에서 번역을 고치거나 복사할 수 있고, '포스트잇 붙이기'를 누르면 그 자리에 포스트잇으로 붙습니다. 메모·번역 포스트잇은 한 번 탭하면 도형처럼 테두리가 나타나 모서리 핸들로 크기, 위쪽 ↻로 회전, ×로 삭제, 몸통을 끌어 이동할 수 있습니다(한 번 더 탭하면 내용 편집). 읽어주기는 선택한 글자를 소리 내어 읽습니다."},
        {"13. 문서 변환 · 내보내기",
         "Office·한글 문서|HWP · HWPX · DOC · DOCX · PPT · PPTX · XLS · XLSX는 PDF로 변환해 문서함에 가져와 엽니다. 서식은 변환 엔진과 글꼴에 따라 달라질 수 있고, HWP·DOC의 본문 미리보기는 글자만 표시합니다.",
         "내보내기·백업|더보기 메뉴에서 기록이 포함된 PDF를 내보내거나, ‘원본 파일 내보내기’로 기록 없는 원본만 저장하거나, 기록을 파일로 백업·복원합니다. ‘인쇄’는 필기와 글상자까지 함께 시스템 인쇄 화면으로 보내며, 거기서 PDF로 저장할 수도 있습니다."}};
    // ---- whole-library backup / restore and app info / update check
    private static final int EXPORT_BACKUP=35,IMPORT_BACKUP=36;
    private static final String RELEASES_URL="https://github.com/hdlee73/Everynote_And/releases";
    private static final String AUTHOR_LINE="만든이 : 이현덕(with Claude), hdlee73@gmail.com";
    private File[] backupAssetDirs(){File img=new File(getFilesDir(),"images");img.mkdirs();File vid=new File(getFilesDir(),"videos");vid.mkdirs();return new File[]{img,recordingsDir(),vid};}
    private void startLibraryBackup(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE,"Everynote-백업-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".zip");startActivityForResult(i,EXPORT_BACKUP);
    }
    private void receiveBackupExport(int result,Intent data){
        if(result!=RESULT_OK||data==null||data.getData()==null)return;final Uri target=data.getData();commitInlineText();toast("전체 문서를 백업하는 중… 잠시 기다려 주세요");
        new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(target)){if(out==null)throw new IOException("저장할 수 없습니다");int n=LibraryBackup.write(this,library,backupAssetDirs(),out,null);runOnUiThread(()->toast("전체 백업 완료 · 문서 "+n+"개 (필기·사진·녹음·동영상 포함)"));}catch(Exception error){runOnUiThread(()->toast("백업 실패: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage())));}},"library-backup").start();
    }
    private void startLibraryRestore(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT_BACKUP);}
    private void receiveBackupImport(int result,Intent data){
        if(result!=RESULT_OK||data==null||data.getData()==null)return;final Uri source=data.getData();
        new AlertDialog.Builder(this).setTitle("모든 문서 복원").setMessage("백업의 문서·필기·사진·녹음·동영상을 문서함으로 복원합니다.\n\n• 추가 복원: 기존 문서는 그대로 두고, 같은 이름은 사본 (1)로 추가\n• 덮어쓰기: 같은 위치·이름의 문서를 백업 내용으로 교체 (열려 있는 문서는 건너뜀)")
            .setPositiveButton("추가 복원",(d,w)->runRestore(source,false)).setNeutralButton("덮어쓰기",(d,w)->runRestore(source,true)).setNegativeButton("취소",null).show();
    }
    private boolean sessionHolds(File f){for(DocumentSession s:sessions)if(s.uri!=null&&"file".equals(s.uri.getScheme())&&f.getPath().equals(s.uri.getPath()))return true;return false;}
    private void runRestore(Uri source,boolean overwrite){
        toast("복원하는 중… 잠시 기다려 주세요");
        new Thread(()->{try(InputStream in=getContentResolver().openInputStream(source)){if(in==null)throw new IOException("파일을 읽을 수 없습니다");
            final LibraryBackup.Result r=LibraryBackup.restore(this,library,backupAssetDirs(),in,overwrite,f->{try{return onUi(()->sessionHolds(f));}catch(Exception e){return true;}},null);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("복원 완료").setMessage("문서 "+r.documents+"개 · 필기 "+r.notes+"개 · 첨부 "+r.assets+"개 · 폴더 "+r.folders+"개"+(r.skipped>0?"\n열려 있어 건너뜀 "+r.skipped+"개":"")+(r.failed>0?"\n실패 "+r.failed+"개":"")).setPositiveButton("확인",null).show();});
        }catch(Exception error){runOnUiThread(()->toast("복원 실패: "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage())));}},"library-restore").start();
    }
    private void showAbout(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(8),dp(22),0);
        TextView info=new TextView(this);info.setText("Everynote\n버전 "+appVersion()+"\n\n"+AUTHOR_LINE);info.setTextSize(15);info.setTextColor(NAVY);info.setTag("about_info");info.setTextIsSelectable(true);box.addView(info);
        TextView releases=new TextView(this);releases.setText("업데이트 정보 (GitHub 릴리스 페이지)");releases.setTextSize(14);releases.setTextColor(ACCENT);releases.setPaintFlags(releases.getPaintFlags()|Paint.UNDERLINE_TEXT_FLAG);releases.setPadding(0,dp(12),0,dp(2));releases.setTag("about_releases");
        releases.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(RELEASES_URL)));}catch(Exception e){toast("브라우저를 열 수 없습니다");}});box.addView(releases);
        final TextView status=new TextView(this);status.setTextSize(14);status.setTextColor(0xFF8E8E93);status.setPadding(0,dp(14),0,dp(6));status.setTag("update_status");box.addView(status);
        String waiting=pendingUpdateVersion();if(waiting!=null){status.setText("새 버전 v"+waiting+" 이(가) 있습니다. 아래 ‘업데이트 확인’을 누르세요");status.setTextColor(ACCENT);}
        android.widget.CheckBox auto=new android.widget.CheckBox(this);auto.setText("앱을 열 때 새 버전 자동 확인");auto.setTextSize(14);auto.setChecked(recentPrefs.getBoolean("auto_update_check",true));auto.setOnCheckedChangeListener((b,on)->recentPrefs.edit().putBoolean("auto_update_check",on).apply());auto.setTag("auto_update");box.addView(auto);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("앱 정보").setView(box).setPositiveButton("업데이트 확인",null).setNegativeButton("닫기",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->checkForUpdate(true,status)));dialog.show();
    }
    private void checkForUpdate(final boolean manual,final TextView status){
        if(status!=null)status.setText("확인 중…");
        new Thread(()->{try{final UpdateChecker.Release r=UpdateChecker.latest();final boolean newer=UpdateChecker.compare(r.version,appVersion())>0;
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;
                recentPrefs.edit().putString("update_version",newer?r.version:"").apply();
                if(!newer){if(status!=null)status.setText("최신 버전입니다 (v"+appVersion()+")");else if(manual)toast("최신 버전입니다");return;}
                if(status!=null){status.setText("새 버전 v"+r.version+" 이(가) 있습니다");status.setTextColor(ACCENT);}
                if(!manual&&status==null){if(r.version.equals(recentPrefs.getString("update_notified","")))return;recentPrefs.edit().putString("update_notified",r.version).apply();notifyUpdate(r);}
                String notes=r.notes==null?"":r.notes.trim();if(notes.length()>500)notes=notes.substring(0,500)+"…";
                new AlertDialog.Builder(this).setTitle("새 버전 v"+r.version).setMessage("현재 v"+appVersion()+(notes.isEmpty()?"":"\n\n"+notes)+"\n\n업데이트를 누르면 설치 파일을 내려받습니다. 내려받은 뒤 알림을 눌러 설치하세요. 문서와 필기는 그대로 유지됩니다.").setPositiveButton("업데이트",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(r.url!=null?r.url:r.page)));}catch(Exception e){toast("브라우저를 열 수 없습니다");}}).setNegativeButton("나중에",null).show();});
        }catch(Exception error){runOnUiThread(()->{String m="업데이트를 확인하지 못했습니다 (인터넷 연결 확인)";if(status!=null)status.setText(m);else if(manual)toast(m);});}},"update-check").start();
    }
    private void autoCheckForUpdate(){
        if(!recentPrefs.getBoolean("auto_update_check",true))return;long now=System.currentTimeMillis();if(now-recentPrefs.getLong("update_checked",0)<6L*3600*1000)return;
        recentPrefs.edit().putLong("update_checked",now).apply();checkForUpdate(false,null);
    }
    /** Newest version found by an earlier check, or null when none is waiting (or it has been installed since). */
    private String pendingUpdateVersion(){String v=recentPrefs==null?"":recentPrefs.getString("update_version","");return v.isEmpty()||UpdateChecker.compare(v,appVersion())<=0?null:v;}
    /** Status-bar notification (Android 13+ asks for permission once); tapping it opens the release page. The in-app dialog still shows either way. */
    private void notifyUpdate(UpdateChecker.Release r){
        try{
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                if(!recentPrefs.getBoolean("notify_asked",false)){recentPrefs.edit().putBoolean("notify_asked",true).apply();requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},77);}
                return;
            }
            android.app.NotificationManager nm=(android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm==null)return;
            if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new android.app.NotificationChannel("updates","앱 업데이트",android.app.NotificationManager.IMPORTANCE_DEFAULT));
            android.app.PendingIntent tap=android.app.PendingIntent.getActivity(this,0,new Intent(Intent.ACTION_VIEW,Uri.parse(r.url!=null?r.url:r.page)),android.app.PendingIntent.FLAG_IMMUTABLE|android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification.Builder b=Build.VERSION.SDK_INT>=26?new android.app.Notification.Builder(this,"updates"):new android.app.Notification.Builder(this);
            b.setSmallIcon(getApplicationInfo().icon).setContentTitle("Everynote 새 버전 v"+r.version).setContentText("눌러서 업데이트를 내려받으세요").setContentIntent(tap).setAutoCancel(true);
            nm.notify(7001,b.build());
        }catch(Exception ignored){}
    }
    private void showHelp(){
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(round(Color.WHITE,20));card.setPadding(dp(22),dp(20),dp(22),dp(6));
        TextView title=new TextView(this);title.setText("Everynote 사용법");title.setTextSize(21);title.setTextColor(NAVY);title.setTypeface(Typeface.DEFAULT_BOLD);title.setGravity(Gravity.START);card.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView version=new TextView(this);version.setText("버전 "+appVersion());version.setTextSize(13);version.setTextColor(0xFF8E8E93);version.setGravity(Gravity.START);version.setPadding(0,dp(2),0,dp(8));card.addView(version,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        for(String[] section:HELP){
            TextView heading=new TextView(this);heading.setText(section[0]);heading.setTextSize(17);heading.setTextColor(ACCENT);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setGravity(Gravity.START);heading.setPadding(0,dp(20),0,dp(4));body.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            for(int k=1;k<section.length;k++){
                int bar=section[k].indexOf('|');String name=section[k].substring(0,bar),text=section[k].substring(bar+1);
                android.text.SpannableStringBuilder line=new android.text.SpannableStringBuilder(name);line.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,name.length(),0);line.append("\n").append(text);
                TextView item=new TextView(this);item.setText(line);item.setTextSize(15);item.setTextColor(0xFF2C2C2E);item.setGravity(Gravity.START);item.setLineSpacing(0,1.3f);item.setPadding(0,dp(8),0,0);body.addView(item,new LinearLayout.LayoutParams(-1,-2));
            }
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(body);card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        View line=new View(this);line.setBackgroundColor(0xFFE5E5EA);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2));lp.topMargin=dp(10);card.addView(line,lp);
        card.addView(dialogButton("확인",ACCENT,true,dialog::dismiss),new LinearLayout.LayoutParams(-1,dp(50)));
        dialog.setContentView(card);dialog.setCanceledOnTouchOutside(true);dialog.show();
        Window window=dialog.getWindow();if(window!=null){android.util.DisplayMetrics m=getResources().getDisplayMetrics();window.setGravity(Gravity.CENTER);window.setLayout(Math.min(m.widthPixels-dp(20),dp(720)),Math.round(m.heightPixels*.92f));}
    }
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception error){return "";}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void closeAllDocuments(){closeSearch();for(DocumentSession s:new ArrayList<>(sessions)){if(s.renderer!=null)s.renderer.close();if(s.descriptor!=null)try{s.descriptor.close();}catch(IOException ignored){}if(s.officePreview!=null)s.officePreview.delete();}sessions.clear();renderer=null;descriptor=null;}
    @Override protected void onStop(){stopInlinePlayer();commitInlineText();onSelectionAdjustStarted();saveSessionState();super.onStop();}
    @Override protected void onDestroy(){stopRecording(true);if(libraryDialog!=null)libraryDialog.dismiss();if(searchCanceled!=null)searchCanceled.set(true);if(hwpConversion!=null)hwpConversion.cancel();saveSessionState();++ocrGeneration;if(speech!=null){speech.stop();speech.shutdown();speech=null;}if(latinRecognizer!=null)latinRecognizer.close();if(koreanRecognizer!=null)koreanRecognizer.close();closeAllDocuments();super.onDestroy();}
}
