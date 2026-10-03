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
    private final class PageListener implements PdfPageView.Listener {
        PdfPageView view;
        private void active(){if(view!=null&&renderer!=null&&pageView!=view){pageView=view;currentPage=view.getPageNumber();activeSession.page=currentPage;updateBookmarkButton();refreshStudyPanel();}}
        @Override public void onHighlightCreated(AnnotationStore.Mark mark){active();MainActivity.this.onHighlightCreated(mark);}
        @Override public void onMarkTapped(AnnotationStore.Mark mark){active();MainActivity.this.onMarkTapped(mark);}
        @Override public void onMemoPointRequested(int page, float x, float y){active();MainActivity.this.onMemoPointRequested(page,x,y);}
        @Override public void onZoomGestureStarted(){active();MainActivity.this.onZoomGestureStarted();}
        @Override public void onPageSwipe(int direction){active();MainActivity.this.onPageSwipe(direction);}
        @Override public void onOutlinePointRequested(int page, float x, float y){active();MainActivity.this.onOutlinePointRequested(page,x,y);}
        @Override public void onInkChanged(){active();MainActivity.this.onInkChanged();}
        @Override public void onTextSelectionFinished(PdfPageView.TextSelection selection, float anchorX, float anchorY){active();MainActivity.this.onTextSelectionFinished(selection,anchorX,anchorY);}
        @Override public void onTranslationTapped(AnnotationStore.TranslationNote note){active();MainActivity.this.onTranslationTapped(note);}
        @Override public void onSelectionAdjustStarted(){active();MainActivity.this.onSelectionAdjustStarted();}
        @Override public void onLassoSelectionFinished(){active();MainActivity.this.onLassoSelectionFinished();}
        @Override public void onElementTapped(AnnotationStore.PageElement element){active();MainActivity.this.onElementTapped(element);}
        @Override public void onBlankLongPress(int page,float x,float y,float viewX,float viewY){active();MainActivity.this.showInsertMenuAt(view,page,x,y,viewX,viewY);}
    }
    private static final class DocumentSession {
        Uri uri; String title; ParcelFileDescriptor descriptor; PdfRenderer renderer;
        AnnotationStore store; int page; File officePreview;
        final Deque<AnnotationStore.InkStroke> redoStrokes=new ArrayDeque<>();
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
    private ImageButton bookmarkButton,memoButton,inkButton,fullscreenExit,previousOverlay,nextOverlay; private LinearLayout header,bottomBar,fullscreenDock,readBar,writeBar; private ImageButton penButton,hlButton,eraserButton; private View dockHandle; private boolean dockShown; private final Runnable dockHider=()->hideFullscreenDock(true); private final java.util.Map<ImageButton,Integer> baseTint=new java.util.HashMap<>(); private boolean writeMode;
    private FrameLayout root; private AnnotationStore store; private boolean highlightMode,memoMode,outlineMode,fullscreen;
    private boolean verticalPageSwipe;
    private boolean fingerInk,swipeEnabled;
    private HwpConversion hwpConversion;
    private final List<DocumentSession> sessions=new ArrayList<>(); private DocumentSession activeSession;
    private LinearLayout tabRow,thumbnailList; private HorizontalScrollView tabStrip; private ScrollView thumbnailPanel;
    private int thumbnailGeneration; private boolean sidebarVisible;
    private int inkMode,inkPen,inkColor=0xFF1C1C1E; private float inkWidth=0.004f;
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
    private LinearLayout searchPanel,searchList,lassoBar; private ScrollView searchScroll; private EditText searchInput; private TextView searchStatus; private ImageButton textButton,lassoButton;
    private final List<SearchScanner.Hit> searchHits=new ArrayList<>(); private int searchCurrent=-1,searchSession,searchDone,searchTotal;
    private boolean searching,searchTruncated,searchPrecise; private DocumentSession searchOwner;
    private int lassoShape; private boolean showAllThumbnails;

    private void translateText(String source,RectF bounds){
        Intent intent=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
        List<android.content.pm.ResolveInfo> handlers=getPackageManager().queryIntentActivities(intent,0);
        android.content.pm.ActivityInfo translation=null;
        for(android.content.pm.ResolveInfo handler:handlers){
            if(!handler.activityInfo.exported)continue;
            String name=handler.loadLabel(getPackageManager()).toString().trim();
            if(name.equalsIgnoreCase("Translate")||name.equals("번역")){
                translation=handler.activityInfo;
                if(handler.activityInfo.packageName.equals("com.google.android.apps.translate"))break;
            }
        }
        if(translation==null){
            new AlertDialog.Builder(this).setTitle("Translate 앱을 찾을 수 없습니다")
                .setMessage("기기의 텍스트 선택 메뉴에 Translate가 나타나면 해당 앱을 활성화하세요. 지금은 기기 내 번역을 사용할 수 있습니다.")
                .setPositiveButton("기기 내 번역",(d,w)->translateOffline(source,bounds)).setNegativeButton("닫기",null).show();return;
        }
        pendingSource=source;pendingBounds=new RectF(bounds);pendingSession=activeSession;pendingPage=currentPage;
        Intent request=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .setClassName(translation.packageName,translation.name)
            .putExtra(Intent.EXTRA_PROCESS_TEXT,source)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,false);
        try{startActivityForResult(request,TRANSLATE_EXTERNAL);}
        catch(ActivityNotFoundException|SecurityException error){pendingSource=null;pendingSession=null;toast("Translate를 열 수 없습니다");}
    }

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
        if(available==TextToSpeech.LANG_MISSING_DATA||available==TextToSpeech.LANG_NOT_SUPPORTED){toast("선택한 발음의 음성이 기기에 없습니다");speechPending=null;return;}
        speech.speak(speechPending,TextToSpeech.QUEUE_FLUSH,null,"pdf-note-selection");speechPending=null;
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

    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);recentPrefs=getSharedPreferences("recent_documents",MODE_PRIVATE);verticalPageSwipe=recentPrefs.getBoolean("vertical_page_swipe",false);fingerInk=recentPrefs.getBoolean("finger_ink",false);swipeEnabled=recentPrefs.getBoolean("page_swipe_enabled_v2",true);twoPage=recentPrefs.getBoolean("two_page",false);library=new LibraryRepository(this);com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());libraryFolder=library.root;lassoShape=recentPrefs.getInt("lasso_shape",PdfPageView.LASSO_FREE);showAllThumbnails=recentPrefs.getBoolean("thumb_all",false);buildUi();pageView.setLassoShape(lassoShape);applyDarkPage();inkPen=recentPrefs.getInt("ink_pen",0);pageView.setInkPen(inkPen);pageView.setFingerInk(fingerInk);pageView.setPageSwipeEnabled(swipeEnabled);pageView.setVerticalPageSwipe(verticalPageSwipe);syncOtherTools();Uri u=getIntent().getData();if(u!=null)openPdf(u);else if(!restoreSession())showWelcome();}
    private void applyKeepAwake(){if(recentPrefs.getBoolean("keep_awake",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onResume(){super.onResume();applyKeepAwake();if(awaitingOfficeReturn){awaitingOfficeReturn=false;root.post(()->new AlertDialog.Builder(this).setTitle("문서로 돌아왔습니다")
        .setMessage("문서 앱에서 PDF로 내보냈다면 파일을 가져와 필기와 주석을 이어갈 수 있습니다.")
        .setPositiveButton("PDF 가져오기",(d,w)->chooseConvertedPdf()).setNegativeButton("나중에",null).show());}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private ImageButton icon(int image,String label,int tint,View.OnClickListener click){ImageButton b=new ImageButton(this);b.setImageResource(image);b.setColorFilter(tint);b.setContentDescription(label);b.setBackgroundColor(Color.TRANSPARENT);b.setScaleType(ImageView.ScaleType.CENTER);b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setOnClickListener(click);return b;}

    private void buildUi(){
        root=new FrameLayout(this);root.setBackgroundColor(0xFFF2F2F7);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(4),0,dp(4),0);header.setBackgroundColor(0xFFF9F9F9);
        ImageButton libraryButton=icon(R.drawable.ic_folder_open,"문서함",NAVY,v->showLibrary());libraryButton.setColorFilter(null);libraryButton.setImageDrawable(new FolderIconDrawable(LibraryRepository.FOLDER_COLORS[1],dp(30)));libraryButton.setBackground(round(Color.WHITE,16));libraryButton.setPadding(dp(5),dp(5),dp(5),dp(5));LinearLayout.LayoutParams libraryParams=new LinearLayout.LayoutParams(dp(42),dp(42));libraryParams.setMargins(dp(8),0,dp(2),0);header.addView(libraryButton,libraryParams);
        titleView=new TextView(this);titleView.setTextColor(NAVY);titleView.setTextSize(15);titleView.setTypeface(Typeface.DEFAULT_BOLD);titleView.setTag("document_title");titleView.setOnClickListener(v->{if(activeSession!=null)renameDocument(activeSession);else showLibrary();});titleView.setSingleLine();titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);titleView.setTextDirection(View.TEXT_DIRECTION_LTR);titleView.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);titleView.setPadding(dp(8),0,dp(8),0);titleView.setBackground(round(Color.WHITE,18));titleView.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*.46f));titleView.setPadding(dp(14),0,dp(14),0);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-2,dp(36));titleParams.setMargins(dp(6),0,dp(6),0);header.addView(titleView,titleParams);header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        header.addView(icon(R.drawable.ic_thumbnails,"페이지 목록",0xFF007AFF,v->toggleSidebar()),new LinearLayout.LayoutParams(dp(44),dp(52)));
        header.addView(icon(R.drawable.ic_search,"문서·필기 검색",0xFF30B0C7,v->searchDocument()),new LinearLayout.LayoutParams(dp(44),dp(52)));
        header.addView(icon(R.drawable.ic_fullscreen,"전체 화면",0xFFAF52DE,v->toggleFullscreen()),new LinearLayout.LayoutParams(dp(44),dp(52)));
        header.addView(icon(R.drawable.ic_more_vert,"도구",NAVY,v->showMainMenu(v,false)),new LinearLayout.LayoutParams(dp(44),dp(52)));content.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        tabStrip=new HorizontalScrollView(this);tabStrip.setHorizontalScrollBarEnabled(false);tabStrip.setBackgroundColor(0xFFF7F7F7);tabRow=new LinearLayout(this);tabRow.setGravity(Gravity.CENTER_VERTICAL);tabRow.setPadding(dp(6),dp(4),dp(6),dp(4));tabStrip.addView(tabRow,new HorizontalScrollView.LayoutParams(-2,-1));content.addView(tabStrip,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout viewerRow=new LinearLayout(this);viewerRow.setOrientation(LinearLayout.HORIZONTAL);
        thumbnailPanel=new ScrollView(this);thumbnailPanel.setBackgroundColor(0xFFF2F2F7);thumbnailList=new LinearLayout(this);thumbnailList.setOrientation(LinearLayout.VERTICAL);thumbnailList.setPadding(dp(8),dp(14),dp(8),dp(96));thumbnailPanel.addView(thumbnailList,new ScrollView.LayoutParams(-1,-2));buildSearchPanel();buildSidePanel();viewerRow.addView(sidePanel,new LinearLayout.LayoutParams(sidePanelWidth(),-1));
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
        };viewportLayer=viewport;installDrop(viewport);LinearLayout papers=new LinearLayout(this);
        PageListener firstListener=new PageListener(),secondListener=new PageListener();
        firstPageView=new PdfPageView(this,firstListener);secondPageView=new PdfPageView(this,secondListener);firstListener.view=firstPageView;secondListener.view=secondPageView;pageView=firstPageView;
        firstPageView.setPageDrag(pageDragHandler);secondPageView.setPageDrag(pageDragHandler);papers.addView(firstPageView,new LinearLayout.LayoutParams(0,-1,1));papers.addView(secondPageView,new LinearLayout.LayoutParams(0,-1,1));secondPageView.setVisibility(twoPage?View.VISIBLE:View.GONE);viewport.addView(papers,new FrameLayout.LayoutParams(-1,-1));
        previousOverlay=icon(R.drawable.ic_chevron_left,"이전 페이지",NAVY,v->animatePage(-1));nextOverlay=icon(R.drawable.ic_chevron_right,"다음 페이지",NAVY,v->animatePage(1));
        ImageButton[] arrows={previousOverlay,nextOverlay};for(int i=0;i<arrows.length;i++){ImageButton arrow=arrows[i];arrow.setBackground(round(0xE6F7F5FF,26));arrow.setAlpha(0.38f);arrow.setElevation(dp(3));FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.CENTER_VERTICAL|(i==0?Gravity.START:Gravity.END));ap.setMargins(dp(8),0,dp(8),0);viewport.addView(arrow,ap);}
        buildLassoBar();FrameLayout.LayoutParams lassoParams=new FrameLayout.LayoutParams(-2,dp(44),Gravity.TOP|Gravity.CENTER_HORIZONTAL);lassoParams.topMargin=dp(8);viewport.addView(lassoBar,lassoParams);
        viewerRow.addView(viewport,new LinearLayout.LayoutParams(0,-1,1));
        pdfArea=new FrameLayout(this);pdfArea.addView(viewerRow,new FrameLayout.LayoutParams(-1,-1));
        studySplit=new LinearLayout(this);studySplit.addView(pdfArea);buildStudyPanel();studySplit.addView(studyPanel);
        content.addView(studySplit,new LinearLayout.LayoutParams(-1,0,1));layoutStudyPanel();
        bottomBar=new LinearLayout(this);bottomBar.setTag("reading_toolbar");bottomBar.setGravity(Gravity.CENTER_VERTICAL);bottomBar.setPadding(dp(6),0,dp(6),0);GradientDrawable barSurface=new GradientDrawable();barSurface.setColor(Color.WHITE);barSurface.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});bottomBar.setBackground(barSurface);bottomBar.setElevation(dp(8));
        readBar=new LinearLayout(this);readBar.setTag("read_bar");readBar.setGravity(Gravity.CENTER_VERTICAL);writeBar=new LinearLayout(this);writeBar.setTag("writing_toolbar");writeBar.setGravity(Gravity.CENTER_VERTICAL);writeBar.setVisibility(View.GONE);
        pageLabel=new TextView(this);pageLabel.setTag("page_indicator");pageLabel.setGravity(Gravity.CENTER);pageLabel.setTextColor(0xFF8E8E93);pageLabel.setTextSize(11);pageLabel.setSingleLine();pageLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);pageLabel.setBackground(round(0xFFF2F2F7,10));pageLabel.setContentDescription("페이지 번호 · 눌러 이동");pageLabel.setOnClickListener(v->{if(renderer==null)showAddDocumentMenu();else goToPage();});LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(86),dp(32));pp.setMargins(0,0,dp(5),0);readBar.addView(pageLabel,pp);
        barIcon(readBar,R.drawable.ic_outline,"문서 개요",0xFF007AFF,v->showOutlineList());
        barIcon(readBar,R.drawable.ic_thumbnails,"보기 방법",0xFF30B0C7,v->showViewMenu(v));
        bookmarkButton=barIcon(readBar,R.drawable.ic_star_outline,"즐겨찾기",0xFFF5A623,v->toggleBookmark());
        barIcon(readBar,R.drawable.ic_insert,"삽입 · 사진 스티커 도형 표",0xFFFF2D55,v->showInsertMenu(v));
        inkButton=barIcon(readBar,R.drawable.ic_ink,"필기 모드",0xFF5856D6,v->setWriteMode(true));
        textButton=barIcon(readBar,R.drawable.ic_text,"타이핑",0xFF34C759,v->toggleTyping());
        barIcon(writeBar,R.drawable.ic_book,"읽기 모드",0xFF007AFF,v->setWriteMode(false));
        penButton=barIcon(writeBar,R.drawable.ic_ink,"펜",0xFF1C1C1E,v->penTap(v));
        hlButton=barIcon(writeBar,R.drawable.ic_highlight,"형광펜",0xFFF5C400,v->highlightTap(v));
        eraserButton=barIcon(writeBar,R.drawable.ic_eraser,"지우개",0xFFFF6B8A,v->{if(renderer==null)toast("문서를 먼저 여세요");else setInkMode(2);});
        lassoButton=barIcon(writeBar,R.drawable.ic_lasso,"올가미 선택",0xFFAF52DE,v->toggleLasso());
        memoButton=barIcon(writeBar,R.drawable.ic_note_add,"메모 추가",0xFFFF9500,v->toggleMemoMode());
        barIcon(writeBar,R.drawable.ic_insert,"삽입 · 사진 스티커 도형 표",0xFFFF2D55,v->showInsertMenu(v));
        barIcon(writeBar,R.drawable.ic_undo,"실행 취소",0xFF8E8E93,v->undoInk());
        barIcon(writeBar,R.drawable.ic_redo,"다시 실행",0xFF8E8E93,v->redoInk());
        bottomBar.addView(readBar,new LinearLayout.LayoutParams(-1,-1));bottomBar.addView(writeBar,new LinearLayout.LayoutParams(-1,-1));content.addView(bottomBar,new LinearLayout.LayoutParams(-1,dp(54)));
        fullscreenDock=new LinearLayout(this);fullscreenDock.setTag("fullscreen_toolbar");fullscreenDock.setGravity(Gravity.CENTER_VERTICAL);fullscreenDock.setPadding(dp(6),0,dp(6),0);fullscreenDock.setBackground(round(0xF5FFFFFF,24));fullscreenDock.setElevation(dp(6));fullscreenDock.setVisibility(View.GONE);
        fullscreenDock.addView(dockIcon(R.drawable.ic_outline,"전체 화면 개요",NAVY,v->showOutlineList()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_thumbnails,"전체 화면 보기 방법",NAVY,v->showViewMenu(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_ink,"전체 화면 필기도구",NAVY,v->penTap(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_note_add,"전체 화면 메모 추가",NAVY,v->toggleMemoMode()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_insert,"전체 화면 삽입",NAVY,v->showInsertMenu(v)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_text,"전체 화면 타이핑",NAVY,v->toggleTyping()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_lasso,"전체 화면 올가미",NAVY,v->toggleLasso()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenDock.addView(dockIcon(R.drawable.ic_more_vert,"전체 화면 메뉴",NAVY,v->showMainMenu(v,true)),new LinearLayout.LayoutParams(dp(44),dp(44)));
        fullscreenExit=dockIcon(R.drawable.ic_fullscreen_exit,"전체 화면 종료",NAVY,v->toggleFullscreen());fullscreenDock.addView(fullscreenExit,new LinearLayout.LayoutParams(dp(44),dp(44)));FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-2,dp(44),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);ep.setMargins(dp(8),0,dp(8),dp(14));root.addView(fullscreenDock,ep);
        fullscreenDock.setOnTouchListener((v,e)->{if(dockShown){v.removeCallbacks(dockHider);if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.postDelayed(dockHider,6000);}return false;});
        dockHandle=new View(this);dockHandle.setTag("fullscreen_handle");dockHandle.setContentDescription("도구 모음 열기 · 위로 쓸어올리기");dockHandle.setBackground(round(0x66000000,3));dockHandle.setVisibility(View.GONE);dockHandle.setOnClickListener(v->showFullscreenDock(false));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(44),dp(5),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);hp.bottomMargin=dp(40);root.addView(dockHandle,hp);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(fullscreen){boolean shown=Build.VERSION.SDK_INT>=30?insets.isVisible(WindowInsets.Type.navigationBars())||insets.isVisible(WindowInsets.Type.statusBars()):insets.getSystemWindowInsetBottom()>0;if(shown){root.removeCallbacks(barHider);root.postDelayed(barHider,400);}}int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());top=b.top;bottom=b.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}if(!fullscreen){header.setPadding(dp(4),top,dp(4),0);header.getLayoutParams().height=dp(52)+top;bottomBar.setPadding(dp(6),0,dp(6),bottom);bottomBar.getLayoutParams().height=dp(54)+bottom;}FrameLayout.LayoutParams dock=(FrameLayout.LayoutParams)fullscreenDock.getLayoutParams();dock.bottomMargin=dp(14)+bottom;fullscreenDock.setLayoutParams(dock);FrameLayout.LayoutParams handle=(FrameLayout.LayoutParams)dockHandle.getLayoutParams();handle.bottomMargin=dp(40)+bottom;dockHandle.setLayoutParams(handle);return insets;});setContentView(root);
    }
    private ImageButton dockIcon(int resource,String description,int tint,View.OnClickListener action){ImageButton button=icon(resource,description,tint,action);button.setPadding(dp(10),dp(10),dp(10),dp(10));return button;}
    private ImageButton barIcon(LinearLayout bar,int resource,String description,int tint,View.OnClickListener action){
        ImageButton button=icon(resource,description,tint,action);button.setPadding(dp(6),dp(6),dp(6),dp(6));baseTint.put(button,tint);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(2),0,dp(2),0);bar.addView(button,p);return button;
    }
    /** Samsung Notes style: reading mode shows page tools, writing mode shows pen tools. */
    private void setWriteMode(boolean on){
        if(on&&renderer==null){toast("문서를 먼저 여세요");return;}
        writeMode=on;readBar.setVisibility(on?View.GONE:View.VISIBLE);writeBar.setVisibility(on?View.VISIBLE:View.GONE);
        if(on){if(inkMode==0&&!highlightMode&&!memoMode&&!outlineMode&&!pageView.isLassoMode())setInkMode(1);else updateToolStates();}
        else if(renderer!=null)setInkMode(0);else updateToolStates();
    }
    private void penTap(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(!writeMode){setWriteMode(true);return;}
        if(!highlightMode&&(inkMode==1||inkMode==3))showPenMenu(anchor);else setInkMode(1);
    }
    private void highlightTap(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        if(highlightMode)showHighlightMenu(anchor);else toggleHighlight();
    }
    private void showPenMenu(View anchor){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(2),dp(4),dp(2),0);
        box.addView(segmented(new String[]{"얇게","보통","굵게","최대"},this::widthIndex,i->{inkWidth=INK_WIDTHS[i];pageView.setInkTool(inkMode,inkColor,inkWidth);syncOtherTools();}));
        TextView penLabel=sectionLabel("펜 종류");penLabel.setPadding(dp(8),dp(6),0,dp(2));box.addView(penLabel);
        box.addView(segmented(AnnotationPainter.PEN_NAMES,()->inkPen,i->{inkPen=i;pageView.setInkPen(i);recentPrefs.edit().putInt("ink_pen",i).apply();syncOtherTools();}));
        java.util.function.IntConsumer pickInk=c->{inkColor=(inkColor&0xFF000000)|(c&0xFFFFFF);pageView.setInkTool(inkMode,inkColor,inkWidth);syncOtherTools();updateInkButton();};
        box.addView(swatches(INK_COLORS,()->inkColor|0xFF000000,pickInk,22));
        box.addView(swatches(INK_COLORS2,()->inkColor|0xFF000000,pickInk,22,1));
        box.addView(opacityBar(()->Color.alpha(inkColor),a->{inkColor=(a<<24)|(inkColor&0xFFFFFF);pageView.setInkTool(inkMode,inkColor,inkWidth);syncOtherTools();updateInkButton();}));
        AnchoredMenu.show(this,anchor,true,AnchoredMenu.rows(AnchoredMenu.Row.custom(box),AnchoredMenu.Row.divider(),new AnchoredMenu.Row("직선",R.drawable.ic_line,()->setInkMode(3)).selected(inkMode==3).tint(inkColor|0xFF000000),new AnchoredMenu.Row("손가락 필기",R.drawable.ic_ink,this::toggleFingerInk).tint(0xFF007AFF).selected(fingerInk)),null);
    }
    private void showHighlightMenu(View anchor){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(2),dp(6),dp(2),0);
        box.addView(swatches(HIGHLIGHT_COLORS,()->selectedColor,c->{selectedColor=c;pageView.setHighlightMode(highlightMode,selectedColor);syncOtherTools();updateInkButton();},30,2));
        AnchoredMenu.show(this,anchor,true,AnchoredMenu.rows(AnchoredMenu.Row.custom(box)),null);
    }
    private void showViewMenu(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        List<AnchoredMenu.Row> rows=AnchoredMenu.rows(
            new AnchoredMenu.Row("페이지 미리보기",R.drawable.ic_thumbnails,this::toggleSidebar).tint(0xFF007AFF).selected(sidebarVisible),
            new AnchoredMenu.Row("두 쪽 보기",R.drawable.ic_book,this::toggleTwoPage).tint(0xFF5856D6).selected(twoPage),
            new AnchoredMenu.Row("페이지로 이동",R.drawable.ic_page,this::goToPage).tint(0xFF30B0C7),
            new AnchoredMenu.Row("전체 화면",R.drawable.ic_fullscreen,this::toggleFullscreen).tint(0xFFAF52DE),
            new AnchoredMenu.Row("여백 자르기",R.drawable.ic_scan,()->{recentPrefs.edit().putBoolean("crop_margins",!cropMargins()).apply();applyCrop();toast(cropMargins()?"문서 여백을 잘라 화면에 꽉 채웁니다":"원래 여백을 그대로 보여줍니다");}).tint(0xFF34C759).selected(cropMargins()),
            new AnchoredMenu.Row("검은 문서 배경",R.drawable.ic_circle,this::toggleDarkPage).tint(0xFF3A3A3C).selected(darkPage()),
            new AnchoredMenu.Row("페이지 넘김 설정",R.drawable.ic_sliders,this::choosePageSwipeDirection).tint(0xFF8E8E93),
            new AnchoredMenu.Row("넘김 효과",R.drawable.ic_sliders,this::choosePageAnimation).tint(0xFF8E8E93),
            AnchoredMenu.Row.divider(),
            new AnchoredMenu.Row("페이지 추가",R.drawable.ic_note_add,()->choosePageToInsert(currentPage)).tint(0xFF34C759),
            new AnchoredMenu.Row("페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(currentPage)).danger());
        AnchoredMenu.show(this,anchor,true,rows,null);
    }
    private List<AnchoredMenu.Row> rowsOf(int category){List<AnchoredMenu.Row> rows=new ArrayList<>();for(Tile t:categoryTiles(category)){AnchoredMenu.Row r=new AnchoredMenu.Row(t.label,t.icon,t.action).selected(t.selected);if(t.tint!=0)r.danger();rows.add(r);}return rows;}
    /** Compact top-right menu: the common actions, with the long tail grouped into sub menus. */
    private void showMainMenu(View anchor,boolean above){
        boolean doc=renderer!=null;boolean awake=recentPrefs.getBoolean("keep_awake",false);
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        rows.add(new AnchoredMenu.Row("문서 추가",R.drawable.ic_note_add,this::showAddDocumentMenu).tint(0xFF007AFF));
        rows.add(new AnchoredMenu.Row("새 노트",R.drawable.ic_compose,this::newNotebook).tint(0xFF34C759));
        if(activeSession!=null)rows.add(new AnchoredMenu.Row("이름 변경",R.drawable.ic_text,()->renameDocument(activeSession)).tint(0xFF8E8E93));
        if(doc){
            rows.add(AnchoredMenu.Row.divider());
            rows.add(new AnchoredMenu.Row("메모·하이라이트",R.drawable.ic_highlight,this::showMarkList).tint(0xFFFF9500));
            rows.add(new AnchoredMenu.Row("책갈피 목록",R.drawable.ic_star,this::showBookmarks).tint(0xFFF5A623));
            rows.add(new AnchoredMenu.Row("번역 포스트잇",R.drawable.ic_translate,this::showTranslations).tint(0xFFAF52DE));
            rows.add(new AnchoredMenu.Row("삽입",R.drawable.ic_copy,()->AnchoredMenu.show(this,anchor,above,rowsOf(2),null)).tint(0xFF5856D6).submenu());
            rows.add(new AnchoredMenu.Row("학습·주석",R.drawable.ic_scan,()->AnchoredMenu.show(this,anchor,above,rowsOf(3),null)).tint(0xFF30B0C7).submenu());
            rows.add(new AnchoredMenu.Row("내보내기·백업",R.drawable.ic_share,()->AnchoredMenu.show(this,anchor,above,rowsOf(4),null)).tint(0xFF007AFF).submenu());
        }
        rows.add(AnchoredMenu.Row.divider());
        rows.add(new AnchoredMenu.Row("화면 켜 둠",R.drawable.ic_clock,()->{recentPrefs.edit().putBoolean("keep_awake",!awake).apply();applyKeepAwake();toast(!awake?"읽는 동안 화면이 꺼지지 않습니다":"화면 자동 꺼짐을 따릅니다");}).tint(0xFF8E8E93).selected(awake));
        rows.add(new AnchoredMenu.Row("사용법",R.drawable.ic_outline,this::showHelp).tint(0xFF8E8E93));
        List<AnchoredMenu.Shortcut> shortcuts=new ArrayList<>();
        shortcuts.add(new AnchoredMenu.Shortcut("문서함",R.drawable.ic_folder_open,false,this::showLibrary));
        shortcuts.add(new AnchoredMenu.Shortcut("문서·필기 검색",R.drawable.ic_search,false,this::searchDocument));
        if(doc)shortcuts.add(new AnchoredMenu.Shortcut("즐겨찾기",store.bookmarks.contains(currentPage)?R.drawable.ic_star:R.drawable.ic_star_outline,store.bookmarks.contains(currentPage),this::toggleBookmark));
        AnchoredMenu.show(this,anchor,above,rows,shortcuts);
    }
    private void showWelcome(){if(writeMode){writeMode=false;readBar.setVisibility(View.VISIBLE);writeBar.setVisibility(View.GONE);}previousOverlay.setVisibility(View.GONE);nextOverlay.setVisibility(View.GONE);titleView.setText("PDF Note");pageLabel.setText("문서 열기");}
    private void saveSessionState(){if(restoringSessions||!importing.isEmpty())return;try{JSONArray a=new JSONArray();for(DocumentSession s:sessions)a.put(new JSONObject().put("uri",s.uri.toString()).put("page",s==activeSession?currentPage:s.page).put("text_only",s.officePreview!=null));recentPrefs.edit().putString("open_sessions",a.toString()).putString("active_uri",activeSession==null?"":activeSession.uri.toString()).apply();}catch(JSONException ignored){}}
    private boolean restoreSession(){
        String raw=recentPrefs.getString("open_sessions",null);if(raw==null)return false;
        restoringSessions=true;try{JSONArray saved=new JSONArray(raw);String active=recentPrefs.getString("active_uri","");for(int i=0;i<saved.length();i++){JSONObject entry=saved.getJSONObject(i);Uri uri=Uri.parse(entry.getString("uri"));openPdf(uri,entry.optBoolean("text_only",false),Math.max(0,entry.optInt("page",0)),uri.toString().equals(active));}}catch(Exception ignored){}finally{restoringSessions=false;}
        if(importing.isEmpty())saveSessionState();return !sessions.isEmpty()||!importing.isEmpty();
    }
    private void choosePdf(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/x-hwp","application/vnd.hancom.hwp","application/vnd.hancom.hwpx","application/octet-stream"});startActivityForResult(i,OPEN_PDF);}
    private void chooseConvertedPdf(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");startActivityForResult(i,OPEN_PDF);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==EXPORT_PDF){receivePdfExport(result,data);return;}if(req==EXPORT_ORIGINAL){receiveOriginalExport(result,data);return;}if(req==IMPORT_IMAGE){if(result==RESULT_OK&&data!=null&&data.getData()!=null)importImage(data.getData());return;}if(req==IMPORT_TEMPLATE){if(result==RESULT_OK&&data!=null&&data.getData()!=null)receiveTemplate(data.getData());return;}if(req==IMPORT_VIDEO){if(result==RESULT_OK&&data!=null&&data.getData()!=null)importVideo(data.getData());return;}if(req==EXPORT_CAPTURE){receiveCaptureExport(result,data);return;}if(req==EXPORT_STUDY||req==IMPORT_SIDECAR){receiveStudyResult(req,result,data);return;}if(req==TRANSLATE_EXTERNAL){receiveExternalTranslation(result,data);return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(req==OPEN_PDF){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}openPdf(u);}else if(req==EXPORT_JSON){try(OutputStream out=getContentResolver().openOutputStream(u,"wt")){if(out==null||pendingJsonExport==null)throw new IOException("다시 백업하세요");out.write(pendingJsonExport.getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("주석을 내보냈습니다");}catch(Exception e){toast("내보내기 실패: "+e.getMessage());}}}
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
            .setMessage("원본 서식·표·그림은 설치된 문서 앱에서 확인할 수 있습니다. 해당 앱에서 PDF로 내보낸 뒤 다시 가져오면 PDF Note에서 필기와 주석을 사용할 수 있습니다.")
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
    private void importPdfToLibrary(Uri source,String title,int page,boolean activate){
        if(!importing.add(source.toString()))return;File destination=libraryFolder!=null&&libraryFolder.isDirectory()?libraryFolder:library.root;boolean announce=!restoringSessions;
        ProgressDialog progress=announce?ProgressDialog.show(this,"PDF 가져오기","문서함에 저장하는 중…",true,false):null;
        new Thread(()->{try{File saved=library.importPdf(source,title,destination);runOnUiThread(()->{importing.remove(source.toString());if(isFinishing()||isDestroyed())return;if(progress!=null)progress.dismiss();openPdf(Uri.fromFile(saved),false,page,activate);if(announce)toast("문서함에 자동 저장했습니다");saveSessionState();});}catch(Exception error){runOnUiThread(()->{importing.remove(source.toString());if(isFinishing()||isDestroyed())return;if(progress!=null)progress.dismiss();toast("가져오기 실패: "+error.getMessage());});}},"library-import").start();
    }
    private void switchDocument(DocumentSession s){commitInlineText();if(searchOwner!=null&&searchOwner!=s)closeSearch();library.opened(s.uri);if(activeSession!=null)activeSession.page=currentPage;activeSession=s;renderer=s.renderer;descriptor=s.descriptor;documentUri=s.uri;documentTitle=s.title;store=s.store;titleView.setText(documentTitle);highlightMode=memoMode=outlineMode=false;inkMode=0;pageView.setLassoMode(false);pageView.stopTextSelection();pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(false);pageView.setInkTool(0,inkColor,inkWidth);updateToolStates();updateInkButton();updateTabs();showPage(Math.min(s.page,renderer.getPageCount()-1));rebuildThumbnails();saveSessionState();}
    private void closeDocument(DocumentSession s){commitInlineText();if(s==searchOwner)closeSearch();int oldIndex=sessions.indexOf(s);sessions.remove(s);if(s.renderer!=null)s.renderer.close();if(s.descriptor!=null)try{s.descriptor.close();}catch(IOException ignored){}if(s.officePreview!=null)s.officePreview.delete();if(s==activeSession){activeSession=null;if(sessions.isEmpty()){renderer=null;descriptor=null;documentUri=null;store=null;firstPageView.clearPage();secondPageView.clearPage();thumbnailList.removeAllViews();refreshStudyPanel();updateTabs();showWelcome();}else switchDocument(sessions.get(Math.max(0,Math.min(oldIndex,sessions.size()-1))));}else updateTabs();saveSessionState();}
    private void updateTabs(){
        tabRow.removeAllViews();View activeTab=null;
        for(DocumentSession session:sessions){
            boolean active=session==activeSession;LinearLayout chip=new LinearLayout(this);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(8),0,dp(0),0);chip.setTag("document_tab");
            GradientDrawable background=round(active?Color.WHITE:0xFFF2F2F7,14);background.setStroke(dp(1),active?0xFFC7C7CC:0xFFE5E5EA);chip.setBackground(background);chip.setElevation(active?dp(2):0);
            ImageView documentIcon=new ImageView(this);documentIcon.setImageResource(R.drawable.ic_document_tab);documentIcon.setColorFilter(active?ACCENT:0xFFAEAEB2);chip.addView(documentIcon,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView name=new TextView(this){@Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);scrollTo(0,0);}};
            name.setTag("document_tab_title");name.setText(session.title);name.setSingleLine();name.setHorizontallyScrolling(false);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);name.setTextDirection(View.TEXT_DIRECTION_LTR);name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);name.setTextColor(active?NAVY:0xFF8E8E93);name.setTextSize(12);name.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);name.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);name.setIncludeFontPadding(false);name.setPadding(dp(6),0,0,0);name.setOnClickListener(v->switchDocument(session));name.setOnLongClickListener(v->{renameDocument(session);return true;});chip.addView(name,new LinearLayout.LayoutParams(dp(96),dp(30)));
            TextView close=new TextView(this);close.setText("×");close.setGravity(Gravity.CENTER);close.setTextSize(19);close.setTextColor(0xFFAEAEB2);close.setContentDescription(session.title+" 닫기");close.setOnClickListener(v->closeDocument(session));chip.addView(close,new LinearLayout.LayoutParams(dp(26),dp(30)));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(30));cp.setMargins(dp(3),0,dp(3),0);tabRow.addView(chip,cp);if(active)activeTab=chip;
        }
        TextView add=new TextView(this);add.setText("＋");add.setGravity(Gravity.CENTER);add.setTextSize(22);add.setTextColor(ACCENT);add.setContentDescription("문서 추가");add.setBackground(round(0xFFE5E5EA,14));add.setOnClickListener(v->showAddDocumentMenu());LinearLayout.LayoutParams plus=new LinearLayout.LayoutParams(dp(30),dp(30));plus.setMargins(dp(4),0,dp(8),0);tabRow.addView(add,plus);
        final View selected=activeTab;if(selected!=null)tabStrip.post(()->tabStrip.smoothScrollTo(Math.max(0,selected.getLeft()-dp(8)),0));
    }
    private String queryName(Uri u){try(android.database.Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}}catch(Exception ignored){}return u.getLastPathSegment()==null?"PDF":u.getLastPathSegment();}
    private void toggleSidebar(){if(sidebarVisible&&panelTab==1)closeSidePanel();else selectPanelTab(1);}
    private void updateThumbnailSelection(){for(int i=0;i<thumbnailList.getChildCount();i++){View v=thumbnailList.getChildAt(i);Object tag=v.getTag();if(!(tag instanceof Integer))continue;boolean selected=((Integer)tag)==currentPage;GradientDrawable bg=round(selected?0xFFE5F0FF:Color.TRANSPARENT,8);if(selected)bg.setStroke(dp(2),ACCENT);v.setBackground(bg);}View selected=thumbnailList.findViewWithTag(currentPage);if(sidebarVisible&&selected!=null)selected.post(()->thumbnailPanel.smoothScrollTo(0,Math.max(0,selected.getTop()-dp(16))));}
    private Bitmap renderPage(PdfRenderer target,int index){try(PdfRenderer.Page page=target.openPage(index)){int width=Math.max(1080,getResources().getDisplayMetrics().widthPixels*(twoPage?1:2));float ratio=Math.min(2.5f,(float)width/page.getWidth());Bitmap image=Bitmap.createBitmap(Math.max(1,(int)(page.getWidth()*ratio)),Math.max(1,(int)(page.getHeight()*ratio)),Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);Matrix matrix=new Matrix();matrix.postScale(ratio,ratio);page.render(image,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return image;}}
    private void showPage(int index){
        commitInlineText();onSelectionAdjustStarted();if(renderer==null||index<0||index>=renderer.getPageCount())return;++ocrGeneration;resetPageTransforms();
        int first=twoPage?(index/2)*2:index;firstPageView.showPage(renderPage(renderer,first),first,store.marks,store.strokes,store.translations);firstPageView.setAnnotationStore(store);
        if(twoPage&&first+1<renderer.getPageCount()){secondPageView.setVisibility(View.VISIBLE);secondPageView.showPage(renderPage(renderer,first+1),first+1,store.marks,store.strokes,store.translations);secondPageView.setAnnotationStore(store);}else{secondPageView.clearPage();secondPageView.setVisibility(twoPage?View.INVISIBLE:View.GONE);}
        pageView=twoPage&&index!=first?secondPageView:firstPageView;currentPage=index;activeSession.page=index;syncOtherTools();
        previousOverlay.setVisibility(first>0?View.VISIBLE:View.GONE);nextOverlay.setVisibility(first+(twoPage?2:1)<renderer.getPageCount()||isNotebook(activeSession)?View.VISIBLE:View.GONE);nextOverlay.setContentDescription(first+(twoPage?2:1)>=renderer.getPageCount()&&isNotebook(activeSession)?"새 페이지 추가":"다음 페이지");
        pageLabel.setText(twoPage?(first+1)+"–"+Math.min(first+2,renderer.getPageCount())+" / "+renderer.getPageCount():(index+1)+" / "+renderer.getPageCount());
        applyCrop();updateBookmarkButton();updateThumbnailSelection();refreshStudyPanel();saveSessionState();applySearchHighlights();
        loadViewText(firstPageView);if(twoPage&&secondPageView.getVisibility()==View.VISIBLE)loadViewText(secondPageView);
    }
    private void loadViewText(PdfPageView view){List<PdfPageView.TextRegion> cached=activeSession.textRegions.get(view.getPageNumber());if(cached!=null)view.setTextRegions(cached,false);else view.post(()->recognizeViewText(view,false));}
    private void syncOtherTools(){if(firstPageView!=null&&secondPageView!=null){PdfPageView other=pageView==firstPageView?secondPageView:firstPageView;other.copyToolsFrom(pageView);}}
    private void toggleTwoPage(){twoPage=!twoPage;recentPrefs.edit().putBoolean("two_page",twoPage).apply();if(renderer!=null)showPage(currentPage);else secondPageView.setVisibility(twoPage?View.INVISIBLE:View.GONE);toast(twoPage?"두 쪽 보기 · 각 페이지를 터치해 필기하세요":"한 쪽 보기");}
    private void toggleHighlight(){if(renderer==null)return;highlightMode=!highlightMode;memoMode=outlineMode=false;stopInk();updateToolStates();pageView.setMemoMode(false);pageView.setOutlineMode(false);pageView.setHighlightMode(highlightMode,selectedColor);toast(highlightMode?"문장을 따라 좌우로 드래그하세요":"하이라이트를 종료했습니다");}
    private void toggleMemoMode(){placementKind="";if(renderer==null)return;memoMode=!memoMode;highlightMode=outlineMode=false;stopInk();updateToolStates();pageView.setHighlightMode(false,selectedColor);pageView.setOutlineMode(false);pageView.setMemoMode(memoMode);toast(memoMode?"메모를 놓을 위치를 탭하세요":"메모 추가를 종료했습니다");}
    private void toggleOutlineMode(){if(renderer==null)return;outlineMode=!outlineMode;highlightMode=memoMode=false;stopInk();pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(outlineMode);updateToolStates();toast(outlineMode?"개요로 저장할 정확한 위치를 탭하세요":"개요 지점 선택을 종료했습니다");}
    private void paintTool(ImageButton button,boolean on,int background,int foreground){if(button==null)return;button.setColorFilter(on?foreground:baseTint.containsKey(button)?baseTint.get(button):NAVY);button.setBackground(on?round(background,22):round(Color.TRANSPARENT,22));}
    private void updateToolStates(){commitInlineText();syncOtherTools();updateInkButton();boolean typing=typingActive(),memo=memoMode&&!typing;paintTool(memoButton,memo,ACTIVE_BG,ACTIVE_FG);paintTool(textButton,typing,ACTIVE_BG,ACTIVE_FG);paintTool(lassoButton,pageView!=null&&pageView.isLassoMode(),ACTIVE_BG,ACTIVE_FG);}
    private void stopInk(){pageView.setLassoMode(false);inkMode=0;pageView.setInkTool(0,inkColor,inkWidth);updateInkButton();}
    private void setInkMode(int mode){placementKind="";if(renderer==null)return;pageView.setLassoMode(false);inkMode=mode;pageView.setDirectTextSelection(false);highlightMode=memoMode=outlineMode=false;pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(false);pageView.setInkTool(mode,inkColor,inkWidth);updateToolStates();updateInkButton();toast(mode==3?"직선: 시작점에서 끝점까지 드래그하세요":mode==1?(fingerInk?"손가락 또는 S펜으로 필기하세요":"S펜으로 필기하세요. 손가락 필기는 필기도구에서 켤 수 있습니다"):mode==2?"지울 획을 터치하세요":"읽기 모드 · 빠르게 스와이프하면 페이지를 넘깁니다");}
    private int soft(int color){return (color&0xFFFFFF)|0x26000000;}
    private void updateInkButton(){updateLassoBar();boolean hl=highlightMode,eraser=inkMode==2&&!hl,pen=!hl&&(inkMode==1||inkMode==3);int penColor=inkColor|0xFF000000,hlColor=selectedColor|0xFF000000;
        if(penButton!=null)baseTint.put(penButton,penColor);if(hlButton!=null)baseTint.put(hlButton,hlColor);
        paintTool(penButton,pen,soft(penColor),penColor);paintTool(hlButton,hl,soft(hlColor),hlColor);paintTool(eraserButton,eraser,0xFFFFE3E8,0xFFFF3B30);
        if(penButton!=null)penButton.setContentDescription("펜");if(inkButton!=null){inkButton.setContentDescription("필기 모드");}}
    private void toggleFingerInk(){fingerInk=!fingerInk;recentPrefs.edit().putBoolean("finger_ink",fingerInk).apply();pageView.setFingerInk(fingerInk);syncOtherTools();toast(fingerInk?"펜·지우개는 손가락으로도 사용합니다. 두 손가락으로 확대하세요":"손가락은 선택·이동, S펜은 필기에 사용합니다");}
    private void undoInk(){if(store==null)return;for(int i=store.strokes.size()-1;i>=0;i--){AnnotationStore.InkStroke s=store.strokes.get(i);if(s.page==currentPage){store.strokes.remove(i);activeSession.redoStrokes.push(s);store.save();pageView.invalidate();toast("마지막 필기를 취소했습니다");return;}}toast("취소할 필기가 없습니다");}
    private void redoInk(){if(activeSession==null||activeSession.redoStrokes.isEmpty()){toast("다시 실행할 필기가 없습니다");return;}AnnotationStore.InkStroke s=activeSession.redoStrokes.pop();store.strokes.add(s);store.save();if(s.page!=currentPage)showPage(s.page);else pageView.invalidate();}
    private void showInkHelp(){new AlertDialog.Builder(this).setTitle("필기 안내").setMessage("• S펜: 필기 또는 지우개\n• 손가락 필기 켜기: 펜·지우개 사용\n• 손가락 필기 끄기: 글자 선택·화면 이동\n• 두 손가락: 확대·이동\n• 필압: 누르는 힘에 따라 선 굵기 변화\n• S펜 측면 버튼: 누르는 동안 임시 지우개\n• 펜 뒤쪽 지우개: 지원 기기에서 자동 인식\n\n일반 정전식 펜과 손가락은 필압을 지원하지 않습니다.").setPositiveButton("확인",null).show();}
    private void toggleBookmark(){if(renderer==null)return;if(!store.bookmarks.add(currentPage))store.bookmarks.remove(currentPage);store.save();updateBookmarkButton();if(sidebarVisible&&!showAllThumbnails)rebuildThumbnails();}
    private void updateBookmarkButton(){boolean marked=renderer!=null&&store.bookmarks.contains(currentPage);bookmarkButton.setImageResource(marked?R.drawable.ic_star:R.drawable.ic_star_outline);bookmarkButton.setColorFilter(marked?0xFFF59E0B:NAVY);bookmarkButton.setContentDescription(marked?"즐겨찾기 해제":"즐겨찾기 추가");}
    /** In full screen the system navigation/status bars must stay hidden, also after a swipe from the screen edge briefly reveals them. */
    private final Runnable barHider=()->{if(!fullscreen)return;if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null)c.hide(WindowInsets.Type.systemBars());}else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);};
    private void showFullscreenDock(boolean brief){
        dockShown=true;if(fullscreen)root.postDelayed(barHider,250);dockHandle.setVisibility(View.GONE);fullscreenDock.removeCallbacks(dockHider);fullscreenDock.setVisibility(View.VISIBLE);fullscreenDock.setAlpha(0f);fullscreenDock.setTranslationY(dp(60));
        fullscreenDock.animate().alpha(1f).translationY(0).setDuration(180).start();fullscreenDock.postDelayed(dockHider,brief?3500:6000);
    }
    private void hideFullscreenDock(boolean animate){
        dockShown=false;fullscreenDock.removeCallbacks(dockHider);dockHandle.setVisibility(View.GONE);
        if(!fullscreen||!animate){fullscreenDock.animate().cancel();fullscreenDock.setVisibility(View.GONE);return;}
        fullscreenDock.animate().alpha(0f).translationY(dp(60)).setDuration(160).withEndAction(()->{if(!dockShown)fullscreenDock.setVisibility(View.GONE);}).start();
    }
    private void toggleFullscreen(){fullscreen=!fullscreen;header.setVisibility(fullscreen?View.GONE:View.VISIBLE);tabStrip.setVisibility(fullscreen?View.GONE:View.VISIBLE);bottomBar.setVisibility(fullscreen?View.GONE:View.VISIBLE);if(fullscreen)showFullscreenDock(true);else hideFullscreenDock(false);if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(fullscreen)c.hide(WindowInsets.Type.systemBars());else c.show(WindowInsets.Type.systemBars());}}else getWindow().getDecorView().setSystemUiVisibility(fullscreen?View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION:View.SYSTEM_UI_FLAG_VISIBLE);root.requestApplyInsets();}
    @Override public void onBackPressed(){if(inlineElement!=null){commitInlineText();return;}if(searchPanel!=null&&searchPanel.getVisibility()==View.VISIBLE){closeSearch();return;}if(fullscreen)toggleFullscreen();else super.onBackPressed();}
    @Override public void onHighlightCreated(AnnotationStore.Mark mark){store.marks.add(mark);store.save();pageView.invalidate();}
    @Override public void onMemoPointRequested(int page,float x,float y){if(!placementKind.isEmpty()){createPlacedElement(page,x,y);return;}AnnotationStore.Mark m=new AnnotationStore.Mark();m.page=page;m.left=Math.max(0f,x-0.025f);m.right=Math.min(1f,x+0.025f);m.top=Math.max(0f,y-0.025f);m.bottom=Math.min(1f,y+0.025f);m.color=selectedColor;m.note="";m.noteOnly=true;
        showMemoEditor("새 메모 포스트잇",m,"저장",note->{if(note.isEmpty())return;m.note=note;store.marks.add(m);store.save();pageView.invalidate();toast("메모 포스트잇을 저장했습니다");},null,null,null,null);}
    @Override public void onMarkTapped(AnnotationStore.Mark mark){editMark(mark);}
    @Override public void onZoomGestureStarted(){if(highlightMode||memoMode||outlineMode){highlightMode=memoMode=outlineMode=false;pageView.setHighlightMode(false,selectedColor);pageView.setMemoMode(false);pageView.setOutlineMode(false);updateToolStates();}}
    @Override public void onPageSwipe(int direction){animatePage(direction);}
    private void resetPageTransforms(){for(PdfPageView v:new PdfPageView[]{firstPageView,secondPageView})if(v!=null){v.animate().cancel();v.setAlpha(1f);v.setTranslationX(0);v.setTranslationY(0);v.setRotationY(0);}}
    private void animatePage(int direction){
        if(pageAnimating||renderer==null)return;int target=twoPage?(currentPage/2)*2+direction*2:currentPage+direction;if(target<0)return;if(target>=renderer.getPageCount()){if(direction>0&&isNotebook(activeSession))appendPage(activeSession,library.paper(new File(activeSession.uri.getPath())));return;}
        pageAnimating=true;
        if(pageAnimStyle()==2){showPage(target);resetPageTransforms();pageAnimating=false;return;}
        if(pageAnimStyle()==0&&!verticalPageSwipe&&curlPage(direction,target))return;
        float offset=dp(26)*direction;
        boolean vertical=verticalPageSwipe;PdfPageView moving=pageView;
        moving.animate().alpha(0.45f).translationX(vertical?0:-offset).translationY(vertical?-offset:0)
            .setDuration(110).withEndAction(()->{
                showPage(target);resetPageTransforms();
                pageView.setAlpha(.45f);pageView.setTranslationX(vertical?0:offset);pageView.setTranslationY(vertical?offset:0);
                pageView.animate().alpha(1f).translationX(0).translationY(0).setDuration(170)
                    .withEndAction(()->{resetPageTransforms();pageAnimating=false;}).start();
            }).start();
    }
    private Bitmap snapshot(View view){Bitmap bitmap=Bitmap.createBitmap(Math.max(1,view.getWidth()),Math.max(1,view.getHeight()),Bitmap.Config.ARGB_8888);bitmap.eraseColor(darkPage()?Color.BLACK:Color.WHITE);view.draw(new Canvas(bitmap));return bitmap;}
    private static Bitmap slice(Bitmap source,int x,int y,int w,int h){Bitmap part=Bitmap.createBitmap(Math.max(1,w),Math.max(1,h),Bitmap.Config.ARGB_8888);new Canvas(part).drawBitmap(source,-x,-y,null);return part;}
    /** The part of the reading area that is really paper: the page rectangles (both pages for a spread), never the screen around them. */
    private RectF curlRegion(View papers,boolean two){
        RectF union=null;PdfPageView[] views=two?new PdfPageView[]{firstPageView,secondPageView}:new PdfPageView[]{firstPageView};
        for(PdfPageView v:views){RectF r=v.pageRect();if(r.isEmpty())r=new RectF(0,0,v.getWidth(),v.getHeight());if(!r.intersect(0,0,v.getWidth(),v.getHeight()))r=new RectF(0,0,v.getWidth(),v.getHeight());r.offset(v.getLeft(),v.getTop());if(union==null)union=new RectF(r);else union.union(r);}
        if(two){union.left=0;union.right=papers.getWidth();}
        return union;
    }
    private PageCurlView dragCurl;private int curlOrigin;private float dragSpan;private boolean curlConsumed;
    private final PdfPageView.PageDrag pageDragHandler=new PdfPageView.PageDrag(){
        @Override public boolean start(int direction){
            if(pageAnimating||renderer==null||verticalPageSwipe||pageAnimStyle()!=0)return false;int target=twoPage?(currentPage/2)*2+direction*2:currentPage+direction;if(target<0||target>=renderer.getPageCount())return false;
            pageAnimating=true;PageCurlView curl=beginCurl(direction,target);if(curl==null){pageAnimating=curlConsumed;return false;}
            dragCurl=curl;dragSpan=Math.max(dp(120),curl.getLayoutParams().width*(twoPage?.5f:1f)*1.1f);return true;
        }
        @Override public void touchAt(float fraction){if(dragCurl!=null)dragCurl.setTouch(fraction);}
        @Override public void move(float distance){if(dragCurl!=null)dragCurl.setProgress(distance/dragSpan);}
        @Override public void end(float velocity){if(dragCurl==null)return;PageCurlView curl=dragCurl;dragCurl=null;float p=curl.progress();boolean commit=velocity>dp(700)||(velocity>-dp(700)&&p>.4f);finishCurl(curl,p,commit?1f:0f);}
    };
    /** Builds the curl overlay for the page rectangle and switches the pages underneath it; returns null when it cannot. */
    private PageCurlView beginCurl(int direction,int target){
        curlConsumed=false;final View papers=viewportLayer==null?null:viewportLayer.getChildAt(0);
        if(papers==null||papers.getWidth()<=0||papers.getHeight()<=0||firstPageView.getWidth()<=0)return null;
        final boolean forward=direction>0,two=twoPage&&secondPageView.getWidth()>0;
        RectF region=curlRegion(papers,two);int rl=Math.round(region.left),rt=Math.round(region.top),w=Math.round(region.width()),h=Math.round(region.height());
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
            int spine=secondPageView.getLeft(),half=Math.min(spine,papers.getWidth()-spine);rl=spine-half;w=half*2;
            Bitmap oldFirst=slice(oldFull,spine-half,rt,half,h),oldSecond=slice(oldFull,spine,rt,half,h),newFirst=slice(newFull,spine-half,rt,half,h),newSecond=slice(newFull,spine,rt,half,h);
            if(forward)curl.setup(oldFirst,newSecond,oldSecond,PageCurlView.paperBack(PageCurlView.mirror(newFirst)),false,.5f);
            else curl.setup(PageCurlView.mirror(oldSecond),PageCurlView.mirror(newFirst),PageCurlView.mirror(oldFirst),PageCurlView.paperBack(newSecond),true,.5f);
        }
        oldFull.recycle();newFull.recycle();
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h,Gravity.TOP|Gravity.START);lp.leftMargin=rl+papers.getLeft();lp.topMargin=rt+papers.getTop();viewportLayer.addView(curl,1,lp);return curl;
    }
    private void finishCurl(PageCurlView curl,float from,float to){
        android.animation.ValueAnimator animator=android.animation.ValueAnimator.ofFloat(from,to);animator.setDuration(Math.max(200,Math.round(1000*Math.abs(to-from))));animator.setInterpolator(from==0f?new android.view.animation.AccelerateDecelerateInterpolator():new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a->curl.setProgress((Float)a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(to<.5f)showPage(curlOrigin);viewportLayer.removeView(curl);curl.release();resetPageTransforms();pageAnimating=false;}});
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
    private void choosePageAnimation(){String[] choices={"책장 넘김 (종이처럼 접히며 넘어감)","슬라이드 (밀리며 나타남)","효과 없음 (바로 전환)"};new AlertDialog.Builder(this).setTitle("넘김 효과").setSingleChoiceItems(choices,pageAnimStyle(),(dialog,which)->{recentPrefs.edit().putInt("page_anim_style",which).apply();dialog.dismiss();toast("넘김 효과: "+choices[which].split(" \\(")[0]);}).setNegativeButton("취소",null).show();}
    private boolean darkPage(){return recentPrefs.getBoolean("dark_page",false);}
    private void applyDarkPage(){boolean on=darkPage();PageCurlView.backTint=0x00FFFFFF;if(firstPageView!=null)firstPageView.setDarkPage(on);if(secondPageView!=null)secondPageView.setDarkPage(on);}
    private void toggleDarkPage(){recentPrefs.edit().putBoolean("dark_page",!darkPage()).apply();applyDarkPage();toast(darkPage()?"문서 배경을 검게 표시합니다. 어두운 글씨 필기는 밝게 보입니다":"문서를 원래 색으로 표시합니다");}
    private boolean cropMargins(){return recentPrefs.getBoolean("crop_margins",true);}
    /** Trims blank page margins so the printed area fills the screen (not for notebooks, where the margins are writing space). */
    private void applyCrop(){
        RectF box=null;
        if(renderer!=null&&cropMargins()&&!isNotebook(activeSession)){box=firstPageView.contentBounds();if(twoPage&&secondPageView.getVisibility()==View.VISIBLE)box.union(secondPageView.contentBounds());}
        firstPageView.setCrop(box);secondPageView.setCrop(box);
    }
    @Override public void onOutlinePointRequested(int page,float x,float y){promptOutline(page,x,y,"");}
    private void promptOutline(int page,float x,float y,String suggested){EditText input=new EditText(this);input.setHint("예: 2. 세부 검토사항");if(suggested!=null&&!suggested.isEmpty())input.setText(suggested.length()>60?suggested.substring(0,60)+"…":suggested);input.setPadding(dp(24),dp(12),dp(24),dp(12));new AlertDialog.Builder(this).setTitle("개요 제목").setView(input).setPositiveButton("저장",(d,w)->{String title=input.getText().toString().trim();if(title.isEmpty())title="페이지 "+(page+1);AnnotationStore.OutlineItem item=new AnnotationStore.OutlineItem();item.page=page;item.x=x;item.y=y;item.title=title;store.outlines.add(item);store.save();if(sidebarVisible&&panelTab==2)rebuildOutlinePanel();outlineMode=false;pageView.setOutlineMode(false);updateToolStates();toast("개요에 저장했습니다");}).setNegativeButton("취소",null).show();}
    @Override public void onInkChanged(){if(store!=null){store.save();if(activeSession!=null)activeSession.redoStrokes.clear();}}
    @Override public void onTextSelectionFinished(PdfPageView.TextSelection selection,float anchorX,float anchorY){showTextSelectionPopup(selection,anchorX,anchorY);}
    @Override public void onTranslationTapped(AnnotationStore.TranslationNote note){editTranslation(note);}
    private void startTextSelection(){if(renderer==null)return;setInkMode(0);pageView.setDirectTextSelection(true);syncOtherTools();recognizePageText(true);toast("텍스트 선택: 단어에서 드래그하세요");}
    private void recognizePageText(boolean announce){++ocrGeneration;recognizeViewText(pageView,announce);if(twoPage)recognizeViewText(pageView==firstPageView?secondPageView:firstPageView,false);}
    private void recognizeViewText(PdfPageView view,boolean announce){if(renderer==null||view.getVisibility()!=View.VISIBLE)return;final DocumentSession session=activeSession;final int page=view.getPageNumber();final int generation=ocrGeneration;Bitmap copy=view.copyPageBitmap();if(copy==null)return;if(announce)toast("글자를 다시 인식하는 중입니다…");if(latinRecognizer==null){latinRecognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);koreanRecognizer=TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());}final int width=copy.getWidth(),height=copy.getHeight();InputImage image=InputImage.fromBitmap(copy,0);latinRecognizer.process(image).addOnCompleteListener(latin->{koreanRecognizer.process(image).addOnCompleteListener(korean->{copy.recycle();if(generation!=ocrGeneration||session!=activeSession||page!=view.getPageNumber())return;Text result=null;if(latin.isSuccessful())result=latin.getResult();if(korean.isSuccessful()&&(result==null||korean.getResult().getText().length()>result.getText().length()))result=korean.getResult();if(result==null){if(announce)toast("글자를 인식하지 못했습니다");return;}List<PdfPageView.TextRegion> regions=makeTextRegions(result,width,height);session.textRegions.put(page,regions);view.setTextRegions(regions,announce);});});}
    private List<PdfPageView.TextRegion> makeTextRegions(Text text,int width,int height){List<PdfPageView.TextRegion> out=new ArrayList<>();for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){android.graphics.Rect lb=line.getBoundingBox();if(lb==null)continue;RectF lineBox=normalized(lb,width,height);for(Text.Element element:line.getElements()){android.graphics.Rect eb=element.getBoundingBox();if(eb!=null&&!element.getText().trim().isEmpty())out.add(new PdfPageView.TextRegion(element.getText().trim(),line.getText().trim(),normalized(eb,width,height),lineBox));}}return out;}
    private RectF normalized(android.graphics.Rect r,int w,int h){return new RectF(Math.max(0f,(float)r.left/w),Math.max(0f,(float)r.top/h),Math.min(1f,(float)r.right/w),Math.min(1f,(float)r.bottom/h));}
    private PopupWindow selectionPopup;
    @Override public void onSelectionAdjustStarted(){if(selectionPopup!=null){selectionPopup.dismiss();selectionPopup=null;}}
    private TextView menuTile(LinearLayout row,String label,int iconId,Runnable action){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);
        tile.setBackground(round(0xFFF4F7FB,16));tile.setContentDescription(label);
        FrameLayout chip=new FrameLayout(this);chip.setBackground(round(0x1F2563EB,12));
        ImageView iconView=new ImageView(this);iconView.setImageResource(iconId);iconView.setColorFilter(ACCENT);
        chip.addView(iconView,new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
        tile.addView(chip,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView title=new TextView(this);title.setText(label);title.setSingleLine();title.setTextColor(NAVY);
        title.setTextSize(12);title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,dp(22));titleParams.topMargin=dp(3);tile.addView(title,titleParams);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(65),1);params.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(tile,params);
        tile.setOnClickListener(v->action.run());
        return title;
    }
    /** One menu for everything: the selection actions on top, then the same insert rows as the long-press menu, in the same card style. */
    private void showTextSelectionPopup(PdfPageView.TextSelection selection,float anchorX,float anchorY){
        onSelectionAdjustStarted();
        final PdfPageView view=pageView;final int page=currentPage;
        Runnable[] after=new Runnable[1];after[0]=()->{onSelectionAdjustStarted();view.clearTextSelectionOverlay();};
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        String[] labels={"하이라이트","복사","번역","읽어주기","단어장","개요","메모","발췌","링크"};
        int[] icons={R.drawable.ic_highlight,R.drawable.ic_copy,R.drawable.ic_translate,R.drawable.ic_speaker,R.drawable.ic_dictionary,R.drawable.ic_outline,R.drawable.ic_note_add,R.drawable.ic_copy,R.drawable.ic_link};
        int[] tints={0xFFF5A623,0xFF8E8E93,0xFF007AFF,0xFF34C759,0xFF30B0C7,0xFF5856D6,0xFFFF9500,0xFFAF52DE,0xFF5856D6};
        Runnable[] actions={()->addOcrHighlights(selection.bounds),()->copySelectedText(selection.text),()->translateText(selection.text,selection.unionBounds),
            ()->readAloud(selection.text),()->openDictionary(selection.text),()->promptOutline(page,selection.unionBounds.left,selection.unionBounds.top,selection.text),
            ()->onMemoPointRequested(page,selection.unionBounds.right,selection.unionBounds.top),
            ()->addStudyEntry(selection.text,selection.unionBounds.left,selection.unionBounds.top,true),()->createHyperlink(selection)};
        for(int i=0;i<labels.length;i++){final int index=i;rows.add(new AnchoredMenu.Row(labels[i],icons[i],()->{actions[index].run();after[0].run();}).tint(tints[i]));}
        rows.add(AnchoredMenu.Row.divider());
        dropTarget=new float[]{page,selection.unionBounds.left,selection.unionBounds.bottom};dropTime=System.currentTimeMillis();
        for(AnchoredMenu.Row row:insertRows())if(!"하이퍼링크".equals(row.label))rows.add(row);
        showMenuAt(view,anchorX,anchorY,rows,view::clearTextSelectionOverlay);
    }
    private void copySelectedText(String text){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("PDF 선택 문장",text));toast("선택한 내용을 복사했습니다");}
    /** Opens the 영어 스터디 app (com.hdlee73.englishstudy). Its dictionary tab searches a copied English word when it comes to the front, so the word is placed on the clipboard first. */
    private void openDictionary(String word){
        String query=word==null?"":word.replaceAll("^[^A-Za-z]+|[^A-Za-z'-]+$","").trim();
        Intent launch=getPackageManager().getLaunchIntentForPackage("com.hdlee73.englishstudy");
        if(launch==null){new AlertDialog.Builder(this).setTitle("영어 스터디 앱이 필요합니다").setMessage("영어 스터디 앱을 설치하면 선택한 단어를 바로 검색할 수 있습니다.\nhttps://github.com/hdlee73/english_study/releases").setPositiveButton("확인",null).show();return;}
        if(!query.isEmpty()){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("PDF 선택 단어",query));}
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(launch);}catch(RuntimeException error){toast("영어 스터디 앱을 열 수 없습니다");}
    }
    private void addOcrHighlights(List<RectF> bounds){for(RectF b:bounds){AnnotationStore.Mark m=new AnnotationStore.Mark();m.page=currentPage;m.left=b.left;m.top=b.top;m.right=b.right;m.bottom=b.bottom;m.color=selectedColor;store.marks.add(m);}store.save();pageView.invalidate();toast("선택한 범위를 하이라이트했습니다");}
    private void translateOffline(String source,RectF bounds){final DocumentSession target=activeSession;final int page=currentPage;boolean korean=source.matches(".*[가-힣].*");TranslatorOptions options=new TranslatorOptions.Builder().setSourceLanguage(korean?TranslateLanguage.KOREAN:TranslateLanguage.ENGLISH).setTargetLanguage(korean?TranslateLanguage.ENGLISH:TranslateLanguage.KOREAN).build();Translator translator=Translation.getClient(options);ProgressDialog progress=ProgressDialog.show(this,"번역","번역 모델을 준비하는 중입니다…",true,false);translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()).onSuccessTask(v->translator.translate(source)).addOnSuccessListener(result->{progress.dismiss();translator.close();if(isFinishing()||isDestroyed()||!sessions.contains(target))return;switchDocument(target);showPage(page);showTranslationResult(source,result,bounds);}).addOnFailureListener(e->{progress.dismiss();translator.close();toast("번역 실패: 인터넷 연결을 확인하세요");});}
    private void showTranslationResult(String source,String translated,RectF bounds){final AnnotationStore targetStore=store;final int targetPage=currentPage;LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(22),dp(4),dp(22),0);TextView original=new TextView(this);original.setText("원문\n"+source);original.setTextColor(0xFF8E8E93);original.setTextSize(14);panel.addView(original);EditText result=new EditText(this);result.setText(translated);result.setHint("번역 앱에서 결과를 복사한 뒤 붙여넣으세요");result.setMinLines(3);result.setMaxLines(7);result.setPadding(dp(16),dp(16),dp(16),dp(16));result.setBackground(round(0xFFFFF7D6,16));result.setGravity(Gravity.TOP);panel.addView(result,new LinearLayout.LayoutParams(-1,-2));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("번역 · 포스트잇").setView(panel).setPositiveButton("포스트잇 저장",(d,w)->{String value=result.getText().toString().trim();if(value.isEmpty())return;AnnotationStore.TranslationNote n=new AnnotationStore.TranslationNote();n.page=targetPage;n.left=bounds.left;n.top=bounds.top;n.right=bounds.right;n.bottom=bounds.bottom;n.source=source;n.translated=value;targetStore.translations.add(n);targetStore.save();pageView.invalidate();toast("번역 포스트잇을 저장했습니다");}).setNeutralButton("붙여넣기",null).setNegativeButton("닫기",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();if(clip!=null&&clip.getItemCount()>0)result.setText(clip.getItemAt(0).coerceToText(this));}));dialog.show();}
    private void editTranslation(AnnotationStore.TranslationNote note){EditText input=new EditText(this);input.setText(note.translated);input.setMinLines(3);new AlertDialog.Builder(this).setTitle("번역 포스트잇 · p."+(note.page+1)).setMessage("원문: "+note.source).setView(input).setPositiveButton("저장",(d,w)->{note.translated=input.getText().toString().trim();store.save();pageView.invalidate();}).setNegativeButton("삭제",(d,w)->{store.translations.remove(note);store.save();pageView.invalidate();toast("번역 포스트잇을 삭제했습니다");}).setNeutralButton("표시 설정",(d,w)->showTranslationDisplayOptions(note)).show();}
    private void showTranslationDisplayOptions(AnnotationStore.TranslationNote note){String[] choices={"펼쳐서 표시","최소화","숨기기"};int checked=!note.visible?2:(note.minimized?1:0);showActionSheet("번역 포스트잇 표시",choices,checked,w->{note.visible=w!=2;note.minimized=w==1;store.save();pageView.invalidate();});}
    private void showTranslations(){if(store==null||store.translations.isEmpty()){toast("저장된 번역 포스트잇이 없습니다");return;}List<AnnotationStore.TranslationNote> items=new ArrayList<>(store.translations);String[] labels=new String[items.size()];for(int i=0;i<items.size();i++){AnnotationStore.TranslationNote n=items.get(i);String state=!n.visible?"숨김":(n.minimized?"최소화":"펼침");labels[i]="p."+(n.page+1)+"  ["+state+"] "+(n.translated.length()>35?n.translated.substring(0,35)+"…":n.translated);}new AlertDialog.Builder(this).setTitle("번역 포스트잇").setItems(labels,(d,i)->{showPage(items.get(i).page);editTranslation(items.get(i));}).show();}
    private void editMark(AnnotationStore.Mark mark){
        showMemoEditor("페이지 "+(mark.page+1)+(mark.noteOnly?" 메모 포스트잇":" 하이라이트"),mark,"저장",text->{mark.note=text;mark.visible=true;store.save();pageView.invalidate();},
            mark.noteOnly?"메모 삭제":"하이라이트 삭제",()->{store.marks.remove(mark);store.save();pageView.invalidate();toast(mark.noteOnly?"메모를 삭제했습니다":"하이라이트를 삭제했습니다");},"표시 설정",()->showMemoDisplayOptions(mark));
    }
    private void showMemoDisplayOptions(AnnotationStore.Mark mark){String[] choices={"펼쳐서 표시","최소화","숨기기"};int checked=!mark.visible?2:(mark.minimized?1:0);showActionSheet("메모 포스트잇 표시",choices,checked,w->{mark.visible=w!=2;mark.minimized=w==1;store.save();pageView.invalidate();});}
    private void showAddDocumentMenu(){showActionSheet("문서 추가",new String[]{"파일 가져오기","저장된 문서 열기","새 노트 만들기"},-1,index->{if(index==0)choosePdf();else if(index==1)showLibrary();else newNotebook();});}
    private void showOutlineList(){
        if(store==null){toast("PDF를 먼저 여세요");return;}
        if(sidebarVisible&&panelTab==2){closeSidePanel();return;}
        selectPanelTab(2);
    }
    private void showOutlineItem(AnnotationStore.OutlineItem item){new AlertDialog.Builder(this).setTitle(item.title).setMessage("페이지 "+(item.page+1)).setPositiveButton("이동",(d,w)->{showPage(item.page);pageView.post(()->pageView.focusOnPoint(item.x,item.y));}).setNegativeButton("삭제",(d,w)->{store.outlines.remove(item);store.save();toast("개요 항목을 삭제했습니다");if(sidebarVisible&&panelTab==2)rebuildOutlinePanel();}).setNeutralButton("취소",null).show();}
    private void choosePageSwipeDirection(){String[] choices={"화살표만 · 드래그 넘김 끄기","수평 · 좌우로 넘기기","수직 · 위아래로 넘기기"};new AlertDialog.Builder(this).setTitle("페이지 넘김").setSingleChoiceItems(choices,!swipeEnabled?0:verticalPageSwipe?2:1,(dialog,which)->{swipeEnabled=which!=0;verticalPageSwipe=which==2;pageView.setVerticalPageSwipe(verticalPageSwipe);pageView.setPageSwipeEnabled(swipeEnabled);recentPrefs.edit().putBoolean("vertical_page_swipe",verticalPageSwipe).putBoolean("page_swipe_enabled_v2",swipeEnabled).apply();syncOtherTools();dialog.dismiss();toast(swipeEnabled?"스와이프로도 페이지를 넘깁니다":"본문의 반투명 화살표로 페이지를 넘기세요");}).setNegativeButton("취소",null).show();}
    private void showMarkList(){List<AnnotationStore.Mark> items=new ArrayList<>(store.marks);if(items.isEmpty()){toast("저장된 하이라이트나 메모가 없습니다");return;}String[] labels=new String[items.size()];for(int i=0;i<items.size();i++){AnnotationStore.Mark mark=items.get(i);String note=mark.note;String state=note==null||note.isEmpty()?"":(!mark.visible?"[숨김] ":(mark.minimized?"[최소화] ":"[펼침] "));labels[i]="p."+(mark.page+1)+"  "+state+(note==null||note.isEmpty()?"(메모 없음)":note);}new AlertDialog.Builder(this).setTitle("메모·하이라이트").setItems(labels,(d,i)->{showPage(items.get(i).page);editMark(items.get(i));}).show();}
    private void showBookmarks(){if(store.bookmarks.isEmpty()){toast("즐겨찾기한 페이지가 없습니다");return;}List<Integer> pages=new ArrayList<>(store.bookmarks);Collections.sort(pages);String[] labels=new String[pages.size()];for(int i=0;i<pages.size();i++)labels[i]="페이지 "+(pages.get(i)+1);new AlertDialog.Builder(this).setTitle("즐겨찾기").setItems(labels,(d,i)->showPage(pages.get(i))).show();}
    private void goToPage(){if(renderer==null)return;EditText input=new EditText(this);input.setInputType(2);input.setHint("1 ~ "+renderer.getPageCount());new AlertDialog.Builder(this).setTitle("페이지로 이동").setView(input).setPositiveButton("이동",(d,w)->{try{showPage(Integer.parseInt(input.getText().toString())-1);}catch(Exception ignored){toast("올바른 페이지를 입력하세요");}}).setNegativeButton("취소",null).show();}
    private void exportAnnotations(){if(documentUri==null)return;try{pendingJsonExport=store.exportJson(documentUri,documentTitle);}catch(JSONException error){toast("백업 실패");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,documentTitle.replaceAll("(?i)\\.pdf$","")+"_annotations.json");startActivityForResult(i,EXPORT_JSON);}
    @Override public void onLassoSelectionFinished(){
        final Bitmap capture;
        try{capture=pageView.captureLasso();}catch(OutOfMemoryError|RuntimeException error){pageView.clearLassoSelection();toast("캡처할 영역을 조금 줄여 주세요");return;}
        if(capture==null)return;
        final String selectedText=pageView.lassoText();final int capturedPage=currentPage;final String capturedTitle=documentTitle;
        final boolean[] handedOff={false};
        ScrollView scroll=new ScrollView(this);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(panel);
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackground(round(0xFFF2F2F7,12));preview.setImageBitmap(capture);panel.addView(preview,new LinearLayout.LayoutParams(-1,dp(190)));
        TextView hint=new TextView(this);hint.setText("선택 영역 · p."+(capturedPage+1));hint.setTextColor(NAVY);hint.setPadding(dp(8),dp(8),dp(8),dp(4));panel.addView(hint);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("올가미 캡처").setView(scroll).setNegativeButton("닫기",null).create();
        String[] labels={"이미지 복사","PNG 저장","이미지 공유","글자 복사","다시 선택","선택 종료"};
        int[] icons={R.drawable.ic_copy,R.drawable.ic_folder_open,R.drawable.ic_share,R.drawable.ic_scan,R.drawable.ic_lasso,R.drawable.ic_check};
        for(int row=0;row<3;row++){LinearLayout group=new LinearLayout(this);panel.addView(group);for(int col=0;col<2;col++){final int index=row*2+col;menuTile(group,labels[index],icons[index],()->{
            if(index<3){handedOff[0]=true;dialog.dismiss();writeCapture(capture,index,capturedTitle,capturedPage);}
            else if(index==3){if(selectedText.isEmpty()){toast("인식된 글자가 없습니다. 이미지 복사를 사용하거나 글자를 다시 인식하세요");return;}copySelectedText(selectedText);dialog.dismiss();}
            else{dialog.dismiss();if(index==5){setInkMode(0);updateToolStates();}}
        });}}
        dialog.setOnDismissListener(d->{preview.setImageDrawable(null);pageView.clearLassoSelection();if(!handedOff[0]&&!capture.isRecycled())capture.recycle();});
        dialog.show();dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.92f),Math.min(dp(600),getResources().getDisplayMetrics().heightPixels-dp(100)));
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
                    if(action==0){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(),"PDF Note 영역 캡처",uri));toast("이미지를 복사했습니다. 이미지 붙여넣기를 지원하는 앱에서 사용하세요");}
                    else if(action==1){pendingCaptureExport=ready;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE,title.replaceAll("(?i)\\.pdf$","")+"_p"+(page+1)+"_capture.png");startActivityForResult(intent,EXPORT_CAPTURE);}
                    else{Intent intent=new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newUri(getContentResolver(),"PDF Note 캡처",uri));try{startActivity(Intent.createChooser(intent,"캡처 이미지 공유"));}catch(ActivityNotFoundException error){toast("이미지를 받을 앱이 없습니다");}}
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
        bar.addView(icon(R.drawable.ic_more_vert,"노트 메뉴",NAVY,v->new AlertDialog.Builder(this).setItems(new String[]{"전체 노트","발췌만 보기","내보내기","닫기"},(d,i)->{if(i<2){basketOnly=i==1;refreshStudyPanel();}else if(i==2)exportStudy();else{studyVisible=false;layoutStudyPanel();}}).show()),new LinearLayout.LayoutParams(dp(44),dp(44)));
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
        EditText comment=new EditText(this);comment.setHint("설명 / Anki 뒷면");comment.setText(entry.comment);comment.setMaxLines(4);panel.addView(comment);
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
        if(store==null)return;new AlertDialog.Builder(this).setTitle("노트·발췌 내보내기").setItems(new String[]{"Markdown (.md)","CSV (.csv)","Excel (.xlsx)","Anki (.tsv · 앞면/뒷면)"},(d,format)->{
            List<AnnotationStore.StudyEntry> entries=new ArrayList<>();for(AnnotationStore.StudyEntry e:store.studyEntries)if(!basketOnly||e.excerpt)entries.add(e);
            if(entries.isEmpty()){toast("내보낼 항목이 없습니다");return;}try{pendingExport=StudyExporter.export(entries,documentTitle,format);}catch(IOException error){toast("내보내기 실패");return;}
            String[] extensions={"md","csv","xlsx","tsv"},types={"text/markdown","text/csv","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/tab-separated-values"};
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
            new AlertDialog.Builder(this).setTitle("주석 백업 복원").setMessage("백업 문서: "+root.optString("document")+"\n현재 문서: "+session.title+"\n\n현재 문서의 주석·노트·발췌를 이 백업으로 교체합니다.")
                .setPositiveButton("복원",(d,w)->{if(!sessions.contains(session))return;try{target.importJson(json,session.renderer.getPageCount());session.redoStrokes.clear();switchDocument(session);toast("주석과 노트를 복원했습니다");}catch(JSONException error){toast("복원 실패: "+error.getMessage());}}).setNegativeButton("취소",null).show();
        }catch(Exception error){toast("백업 읽기 실패: "+error.getMessage());}
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
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);EditText name=new EditText(this);name.setSingleLine();name.setHint("노트 이름");name.setText("새 노트");name.setPadding(dp(18),dp(12),dp(18),dp(12));panel.addView(name,new LinearLayout.LayoutParams(-1,dp(56)));PaperChoiceView paper=new PaperChoiceView(this);paper.onTemplateRequest(()->requestTemplate(paper));panel.addView(paper);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("새 노트").setView(panel).setPositiveButton("만들기",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String title=NotebookFiles.name(name.getText().toString());NotebookFiles.Paper selected=paper.paper();dialog.getButton(-1).setEnabled(false);new Thread(()->{try{File file=library.createNote(folder,title,selected);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;dialog.dismiss();refresh.run();if(libraryDialog!=null)libraryDialog.dismiss();openPdf(Uri.fromFile(file));toast("마지막 장에서 넘기면 새 페이지가 추가됩니다");});}catch(Exception error){runOnUiThread(()->{dialog.getButton(-1).setEnabled(true);name.setError(error.getMessage());});}},"new-notebook").start();}catch(Exception error){name.setError(error.getMessage());}}));dialog.show();
    }
    private boolean isNotebook(DocumentSession session){return session!=null&&library.managed(session.uri)&&library.paper(new File(session.uri.getPath()))!=null;}
    private void chooseAddedPage(){choosePageToInsert(renderer==null?0:renderer.getPageCount()-1);}
    private void appendPage(DocumentSession session,NotebookFiles.Paper paper){insertPage(session,paper,session.renderer.getPageCount()-1);}
    /** Asks for the paper (unless the note already has one) and inserts a blank page after {@code afterIndex}. */
    private void choosePageToInsert(int afterIndex){
        if(activeSession==null)return;final DocumentSession session=activeSession;
        if(!library.managed(session.uri)){toast("문서함에 저장한 뒤 페이지를 추가하세요");return;}
        NotebookFiles.Paper same=library.paper(new File(session.uri.getPath()));if(same!=null){insertPage(session,same,afterIndex);return;}
        PaperChoiceView paper=new PaperChoiceView(this);paper.onTemplateRequest(()->requestTemplate(paper));new AlertDialog.Builder(this).setTitle("추가할 페이지 · p."+(afterIndex+1)+" 뒤").setView(paper).setPositiveButton("추가",(d,w)->{try{insertPage(session,paper.paper(),afterIndex);}catch(IllegalArgumentException error){toast(error.getMessage());}}).setNegativeButton("취소",null).show();
    }
    private void insertPage(DocumentSession session,NotebookFiles.Paper paper,int afterIndex){
        final File file=new File(session.uri.getPath());
        modifyPages(session,"새 페이지를 추가하는 중…",()->library.insertPage(file,paper,afterIndex),()->session.store.insertPageAfter(afterIndex),afterIndex+1,"페이지 추가 실패");
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
    private void showPageMenu(int page){
        if(renderer==null)return;
        Section section=new Section(null);
        section.add(new Tile("이 페이지로 이동",R.drawable.ic_page,()->showPage(page)));
        section.add(new Tile("뒤에 페이지 추가",R.drawable.ic_note_add,()->choosePageToInsert(page)));
        section.add(new Tile("이 페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(page)).tint(0xFFFF3B30));
        List<Section> sections=new ArrayList<>();sections.add(section);showSheet("페이지 "+(page+1),sections);
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
            float ratio=kind.equals("youtube")?16f/9f:1f;if(!kind.equals("sticker")&&!kind.equals("youtube")){File file=new File(new File(getFilesDir(),"images"),element.asset);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),o);if(o.outWidth>0&&o.outHeight>0)ratio=o.outWidth/(float)o.outHeight;}
            float w=kind.equals("sticker")?.16f:.5f,h=w*pageRatio/ratio;if(h>.6f){h=.6f;w=h*ratio/pageRatio;}
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
        if("hyperlink".equals(element.kind)){showHyperlinkMenu(element);return;}
        String kind=element.kind;
        String[] labels=kind.equals("youtube")?new String[]{"유튜브에서 열기","위치·크기","삭제"}:kind.equals("shape")?new String[]{"색·선 굵기","위치·크기","삭제"}:kind.equals("table")?new String[]{"셀 내용 편집","행·열·색상","위치·크기","삭제"}:kind.equals("image")||kind.equals("sticker")?new String[]{"위치·크기","삭제"}:kind.equals("video")?new String[]{"재생","위치·크기","삭제"}:kind.equals("link")?new String[]{"링크 열기","수정","위치·크기","삭제"}:new String[]{"수정","위치·크기","삭제"};
        new AlertDialog.Builder(this).setTitle("페이지 "+(element.page+1)).setItems(labels,(d,index)->{String action=labels[index];
            if(action.equals("링크 열기")){if(validWebUrl(element.text))try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(element.text)));}catch(ActivityNotFoundException error){toast("링크를 열 앱이 없습니다");}}
            else if(action.equals("재생"))showVideoPlayer(element);
            else if(action.equals("유튜브에서 열기"))openYoutube(element.text);
            else if(action.equals("색·선 굵기"))showShapeDialog(element);
            else if(action.equals("셀 내용 편집"))editTableCells(element);
            else if(action.equals("행·열·색상"))showTableDialog(element);
            else if(action.equals("수정"))editPageElement(element,false);
            else if(action.equals("위치·크기"))editElementGeometry(element);
            else deleteElement(element);}).show();
    }
    private void deleteElement(AnnotationStore.PageElement element){
        if(store==null)return;
        if(element.kind.equals("hyperlink")){store.elements.removeIf(e->e.kind.equals("hyperlink")&&e.page==element.page&&e.color==element.color&&e.text.equals(element.text));}
        else store.elements.remove(element);
        if(element.kind.equals("video")){File video=new File(new File(getFilesDir(),"videos"),element.text);video.delete();}
        store.save();pageView.selectElement(null);redrawPages();
    }
    // ---- pictures, stickers, videos
    private void pickImage(){if(renderer==null){toast("문서를 먼저 여세요");return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),IMPORT_IMAGE);}
    private void pickVideo(){if(renderer==null){toast("문서를 먼저 여세요");return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"),IMPORT_VIDEO);}
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
        TextView mine=pill("내 이미지로 스티커 만들기","이미지 선택",ACTIVE_BG,ACTIVE_FG,v->{if(holder[0]!=null)holder[0].dismiss();pickImage();});LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(44));mp.topMargin=dp(10);box.addView(mine,mp);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        holder[0]=new AlertDialog.Builder(this).setTitle("스티커").setView(scroll).setNegativeButton("닫기",null).create();holder[0].show();
    }
    private void importVideo(Uri source){
        final DocumentSession session=activeSession;toast("동영상을 가져오는 중…");
        new Thread(()->{try{
            File folder=new File(getFilesDir(),"videos");folder.mkdirs();String name=UUID.randomUUID()+".mp4";File target=new File(folder,name);long copied=0;
            try(InputStream in=getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[1<<16];int n;while((n=in.read(buffer))>0){out.write(buffer,0,n);copied+=n;if(copied>600L*1024*1024)throw new IOException("600MB 이하의 동영상만 넣을 수 있습니다");}}
            catch(IOException error){target.delete();throw error;}
            Bitmap frame=null;android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
            try{retriever.setDataSource(target.getPath());frame=retriever.getFrameAtTime(500000,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);}catch(RuntimeException ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
            if(frame==null){target.delete();throw new IOException("동영상을 읽을 수 없습니다");}
            float shrink=Math.min(1f,900f/Math.max(frame.getWidth(),frame.getHeight()));if(shrink<1f){Bitmap small=Bitmap.createScaledBitmap(frame,Math.max(1,Math.round(frame.getWidth()*shrink)),Math.max(1,Math.round(frame.getHeight()*shrink)),true);frame.recycle();frame=small;}
            File images=new File(getFilesDir(),"images");images.mkdirs();String thumb=UUID.randomUUID()+".png";
            try(OutputStream out=new FileOutputStream(new File(images,thumb))){if(!frame.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("미리보기 저장 실패");}finally{frame.recycle();}
            runOnUiThread(()->{if(session!=null&&sessions.contains(session)){switchDocument(session);placementText=name;placeOrDrop("video",thumb);}});
        }catch(Exception error){runOnUiThread(()->toast("동영상 가져오기 실패: "+error.getMessage()));}},"video-import").start();
    }
    private void showVideoPlayer(AnnotationStore.PageElement element){
        File file=new File(new File(getFilesDir(),"videos"),element.text);if(!file.isFile()){toast("동영상 파일을 찾을 수 없습니다");return;}
        final Dialog dialog=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        VideoView video=new VideoView(this);MediaController controller=new MediaController(this);controller.setAnchorView(video);video.setMediaController(controller);video.setVideoURI(Uri.fromFile(file));frame.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        ImageButton close=icon(R.drawable.ic_close,"동영상 닫기",Color.WHITE,v->dialog.dismiss());FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.END);cp.setMargins(0,dp(24),dp(8),0);frame.addView(close,cp);
        dialog.setContentView(frame);dialog.setOnDismissListener(d->video.stopPlayback());video.setOnPreparedListener(m->video.start());dialog.show();
    }
    // ---- insert menu, shapes, tables, drag and drop
    private List<AnchoredMenu.Row> insertRows(){
        return AnchoredMenu.rows(
            new AnchoredMenu.Row("사진·이미지",R.drawable.ic_image,this::pickImage).tint(0xFF007AFF),
            new AnchoredMenu.Row("스티커",R.drawable.ic_sticker,this::showStickerPicker).tint(0xFFFF9500),
            new AnchoredMenu.Row("도형",R.drawable.ic_rect,()->showShapeDialog(null)).tint(0xFFAF52DE),
            new AnchoredMenu.Row("표",R.drawable.ic_thumbnails,()->showTableDialog(null)).tint(0xFF30B0C7),
            new AnchoredMenu.Row("동영상",R.drawable.ic_video,this::pickVideo).tint(0xFFFF3B30),
            new AnchoredMenu.Row("유튜브 링크",R.drawable.ic_video,this::askYoutube).tint(0xFFFF0000),
            new AnchoredMenu.Row("하이퍼링크",R.drawable.ic_link,this::startHyperlink).tint(0xFF5856D6),
            new AnchoredMenu.Row("붙여넣기",R.drawable.ic_copy,this::pasteImage).tint(0xFF8E8E93));
    }
    private void showInsertMenu(View anchor){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        dropTarget=null;
        AnchoredMenu.show(this,anchor,true,insertRows(),null);
    }
    private View tapAnchor;
    /** Long press on empty paper: the same insert menu, and whatever is chosen is placed right there. */
    void showInsertMenuAt(PdfPageView view,int page,float x,float y,float viewX,float viewY){
        if(renderer==null||view==null||viewportLayer==null)return;
        dropTarget=new float[]{page,x,y};dropTime=System.currentTimeMillis();
        showMenuAt(view,viewX,viewY,insertRows(),null);
    }
    /** Shows a floating menu card next to a point inside a page view (above the point when it is in the lower half). */
    private void showMenuAt(PdfPageView view,float viewX,float viewY,List<AnchoredMenu.Row> rows,Runnable onDismiss){
        if(view==null||viewportLayer==null)return;
        View papers=viewportLayer.getChildAt(0);
        if(tapAnchor==null){tapAnchor=new View(this);viewportLayer.addView(tapAnchor,new FrameLayout.LayoutParams(dp(2),dp(2),Gravity.TOP|Gravity.START));}
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)tapAnchor.getLayoutParams();lp.leftMargin=Math.round(viewX+view.getLeft()+papers.getLeft());lp.topMargin=Math.round(viewY+view.getTop()+papers.getTop());tapAnchor.setLayoutParams(lp);
        final boolean above=viewY>view.getHeight()*.5f;
        tapAnchor.post(()->{PopupWindow w=AnchoredMenu.show(this,tapAnchor,above,rows,null);if(onDismiss!=null){selectionPopup=w;w.setOnDismissListener(()->{if(selectionPopup==w)selectionPopup=null;onDismiss.run();});}});
    }
    private LinearLayout colorRow(int[] colors,int[] chosen){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);final View[] swatches=new View[colors.length+1];final Runnable[] refresh=new Runnable[1];
        refresh[0]=()->{boolean custom=true;for(int i=0;i<colors.length;i++){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);boolean none=(colors[i]>>>24)==0;d.setColor(none?Color.WHITE:colors[i]);boolean on=chosen[0]==colors[i];if(on)custom=false;d.setStroke(dp(on?3:1),on?ACCENT:0xFFC7C7CC);swatches[i].setBackground(d);}
            GradientDrawable m=new GradientDrawable();m.setShape(GradientDrawable.OVAL);
            if(custom){m.setColor(chosen[0]);m.setStroke(dp(3),ACCENT);}else{m.setGradientType(GradientDrawable.SWEEP_GRADIENT);m.setColors(new int[]{0xFFFF3B30,0xFFFFCC00,0xFF34C759,0xFF00C7BE,0xFF007AFF,0xFFAF52DE,0xFFFF3B30});m.setStroke(dp(1),0xFFC7C7CC);}
            swatches[colors.length].setBackground(m);};
        for(int i=0;i<colors.length;i++){final int color=colors[i];View v=new View(this);v.setContentDescription((color>>>24)==0?"없음":"색상");swatches[i]=v;v.setOnClickListener(x->{chosen[0]=color;refresh[0].run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(32),1);lp.setMargins(dp(3),dp(2),dp(3),dp(2));row.addView(v,lp);}
        View more=new View(this);more.setContentDescription("다른 색·투명도 선택");swatches[colors.length]=more;more.setOnClickListener(x->ColorPicker.show(this,"색·투명도",chosen[0]==0?0x80007AFF:chosen[0],true,c->{chosen[0]=c;refresh[0].run();}));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(32),1);mp.setMargins(dp(3),dp(2),dp(3),dp(2));row.addView(more,mp);
        refresh[0].run();return row;
    }
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
        LinearLayout sizes=new LinearLayout(this);sizes.setGravity(Gravity.CENTER_VERTICAL);
        EditText rowsInput=new EditText(this),colsInput=new EditText(this);rowsInput.setInputType(2);colsInput.setInputType(2);rowsInput.setText(String.valueOf(base.rows));colsInput.setText(String.valueOf(base.cols));rowsInput.setGravity(Gravity.CENTER);colsInput.setGravity(Gravity.CENTER);
        TextView a=new TextView(this);a.setText("행 ");TextView b=new TextView(this);b.setText("   열 ");sizes.addView(a);sizes.addView(rowsInput,new LinearLayout.LayoutParams(dp(64),-2));sizes.addView(b);sizes.addView(colsInput,new LinearLayout.LayoutParams(dp(64),-2));
        box.addView(sizes);
        box.addView(sectionLabel("선 색"));box.addView(colorRow(lineColors,line));
        box.addView(sectionLabel("머리글 칸 색 (첫 줄)"));box.addView(colorRow(headColors,head));
        box.addView(sectionLabel("바탕 색"));box.addView(colorRow(fillColors,fill));
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle(existing==null?"표 만들기":"표 모양 수정").setView(scroll).setPositiveButton(existing==null?"넣기":"적용",(d,w)->{
            int r=3,c=3;try{r=Integer.parseInt(rowsInput.getText().toString().trim());c=Integer.parseInt(colsInput.getText().toString().trim());}catch(NumberFormatException ignored){}
            r=Math.max(1,Math.min(30,r));c=Math.max(1,Math.min(12,c));
            Shapes.Table t=base.resized(r,c);t.line=line[0];t.head=head[0];t.fill=fill[0];
            if(existing==null){placementText=t.serialize();placeElement("table","");}
            else{existing.text=t.serialize();store.save();redrawPages();}
        }).setNegativeButton("취소",null).show();
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
        new AlertDialog.Builder(this).setTitle("표 내용").setView(scroll).setPositiveButton("저장",(d,w)->{for(int i=0;i<inputs.length;i++)t.cells[i]=inputs[i].getText().toString();element.text=t.serialize();store.save();redrawPages();}).setNegativeButton("취소",null).show();
    }
    private static String youtubeId(String text){
        if(text==null)return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^\\s]*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})").matcher(text.trim());
        return m.find()?m.group(1):null;
    }
    private void askYoutube(){
        if(renderer==null){toast("문서를 먼저 여세요");return;}
        EditText input=new EditText(this);input.setSingleLine();input.setHint("https://youtu.be/… 또는 youtube.com/watch?v=…");input.setPadding(dp(24),dp(12),dp(24),dp(12));
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData data=clipboard.getPrimaryClip();if(data!=null&&data.getItemCount()>0&&data.getItemAt(0).getText()!=null&&youtubeId(data.getItemAt(0).getText().toString())!=null)input.setText(data.getItemAt(0).getText());
        new AlertDialog.Builder(this).setTitle("유튜브 링크").setView(input).setPositiveButton("넣기",(d,w)->{String id=youtubeId(input.getText().toString());if(id==null)toast("유튜브 주소를 입력하세요");else importYoutube(id);}).setNegativeButton("취소",null).show();
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
        Uri uri=null;
        for(int i=0;i<clip.getItemCount()&&uri==null;i++){
            ClipData.Item item=clip.getItemAt(i);
            if(item.getUri()!=null)uri=item.getUri();
            else{
                String html=item.getHtmlText();CharSequence text=item.getText();
                if(html!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("src=[\"']([^\"']+)[\"']").matcher(html);if(m.find())uri=Uri.parse(m.group(1));}
                if(uri==null&&text!=null&&text.toString().trim().matches("https?://\\S+"))uri=Uri.parse(text.toString().trim());
            }
        }
        if(uri==null){toast("끌어 놓은 항목에서 이미지나 동영상을 찾지 못했습니다");return false;}
        try{requestDragAndDropPermissions(e);}catch(RuntimeException ignored){}
        View papers=viewportLayer.getChildAt(0);float px=e.getX()-papers.getLeft(),py=e.getY()-papers.getTop();
        PdfPageView hit=firstPageView;if(twoPage&&secondPageView.getVisibility()==View.VISIBLE&&px>=secondPageView.getLeft())hit=secondPageView;
        float[] n=hit.toPage(px-hit.getLeft(),py-hit.getTop());dropTarget=new float[]{hit.getPageNumber(),n[0],n[1]};dropTime=System.currentTimeMillis();
        String scheme=uri.getScheme()==null?"":uri.getScheme();final Uri source=uri;
        String ytId=youtubeId(uri.toString());if(ytId!=null){importYoutube(ytId);return true;}
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
            target.save();redrawPages();toast("링크를 만들었습니다. 파란 표시가 붙은 글자를 탭하면 열립니다");
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
    private void showHyperlinkMenu(AnnotationStore.PageElement element){
        String title=describeLink(element.text);String[] labels={"열기","링크 수정","링크 삭제"};
        new AlertDialog.Builder(this).setTitle(title.length()>60?title.substring(0,60)+"…":title).setItems(labels,(d,index)->{
            if(index==0)openHyperlink(element);
            else if(index==1)chooseLinkTarget(link->{String old=element.text;for(AnnotationStore.PageElement e:store.elements)if(e.kind.equals("hyperlink")&&e.page==element.page&&e.color==element.color&&e.text.equals(old))e.text=link;store.save();redrawPages();});
            else deleteElement(element);
        }).show();
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
    private static final String[] FONT_IDS={"sans","serif","mono","hand"};
    private static final String[] FONT_NAMES={"고딕","명조","고정폭","손글씨"};
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

    /** Bottom sheet in the One UI style: flat white surface, small gray section captions, round icon tiles. */
    private Dialog showSheet(String title,List<Section> sections){
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setTag("menu_sheet");
        GradientDrawable surface=new GradientDrawable();surface.setColor(Color.WHITE);float corner=dp(24);surface.setCornerRadii(new float[]{corner,corner,corner,corner,0,0,0,0});sheet.setBackground(surface);
        sheet.setPadding(0,dp(8),0,dp(10));
        View grabber=new View(this);grabber.setBackground(round(0xFFC7C7CC,2));
        LinearLayout.LayoutParams grabberParams=new LinearLayout.LayoutParams(dp(36),dp(4));grabberParams.gravity=Gravity.CENTER_HORIZONTAL;grabberParams.bottomMargin=dp(2);sheet.addView(grabber,grabberParams);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),0,dp(8),0);
        TextView heading=new TextView(this);heading.setText(title);heading.setTextSize(17);heading.setTextColor(NAVY);heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setGravity(Gravity.CENTER_VERTICAL);header.addView(heading,new LinearLayout.LayoutParams(0,dp(42),1));
        ImageButton closeButton=icon(R.drawable.ic_close,"닫기",NAVY,v->dialog.dismiss());closeButton.setPadding(dp(10),dp(10),dp(10),dp(10));header.addView(closeButton,new LinearLayout.LayoutParams(dp(40),dp(40)));sheet.addView(header,new LinearLayout.LayoutParams(-1,-2));
        MaxHeightScroll scroll=new MaxHeightScroll(this,Math.round(getResources().getDisplayMetrics().heightPixels*.72f));scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),0,dp(12),dp(4));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        int widthDp=getResources().getConfiguration().screenWidthDp;int columns=widthDp>=840?7:widthDp>=520?6:5;
        for(int s=0;s<sections.size();s++){
            Section section=sections.get(s);
            LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(0,dp(4),0,dp(2));
            if(s>0){View line=new View(this);line.setBackgroundColor(0xFFE5E5EA);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(dp(8),dp(4),dp(8),dp(6));group.addView(line,lp);}
            if(section.title!=null){TextView label=new TextView(this);label.setText(section.title);label.setTextSize(12);label.setTextColor(0xFF8E8E93);label.setPadding(dp(8),dp(4),0,dp(2));group.addView(label);}
            if(section.custom!=null)group.addView(section.custom,new LinearLayout.LayoutParams(-1,-2));
            for(int start=0;start<section.tiles.size();start+=columns){
                LinearLayout row=new LinearLayout(this);group.addView(row,new LinearLayout.LayoutParams(-1,-2));
                for(int i=0;i<columns;i++){
                    if(start+i<section.tiles.size())row.addView(sheetTile(dialog,section.tiles.get(start+i)),new LinearLayout.LayoutParams(0,-2,1));
                    else row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
                }
            }
            body.addView(group,new LinearLayout.LayoutParams(-1,-2));
        }
        sheet.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        dialog.setContentView(sheet);dialog.setCanceledOnTouchOutside(true);dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(600)),ViewGroup.LayoutParams.WRAP_CONTENT);}
        return dialog;
    }
    private View sheetTile(Dialog dialog,Tile tile){
        int glyphColor=tile.tint!=0?tile.tint:NAVY;
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);cell.setPadding(dp(1),dp(6),dp(1),dp(4));cell.setContentDescription(tile.label);
        FrameLayout chip=new FrameLayout(this);GradientDrawable disc=new GradientDrawable();disc.setShape(GradientDrawable.OVAL);disc.setColor(tile.selected?ACTIVE_BG:0xFFF2F2F7);if(tile.selected)disc.setStroke(dp(2),ACCENT);chip.setBackground(disc);
        ImageView glyph=new ImageView(this);glyph.setImageResource(tile.icon);glyph.setColorFilter(tile.selected?ACTIVE_FG:glyphColor);
        chip.addView(glyph,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));cell.addView(chip,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView name=new TextView(this);name.setText(tile.label);name.setTextSize(11);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setTextColor(tile.selected?ACTIVE_FG:NAVY);
        LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-1,-2);nameParams.topMargin=dp(4);cell.addView(name,nameParams);
        cell.setOnClickListener(v->{if(!tile.keepOpen)dialog.dismiss();tile.action.run();});
        return cell;
    }
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose){return swatches(colors,current,choose,32);}
    /** A "투명도 NN%" slider (10-100%) for colours that carry their own alpha. */
    private LinearLayout opacityBar(java.util.function.IntSupplier alpha,java.util.function.IntConsumer set){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(8),dp(2),dp(8),dp(4));
        TextView label=new TextView(this);label.setTextSize(12);label.setTextColor(0xFF8E8E93);label.setText("투명도 "+Math.round(alpha.getAsInt()*100f/255f)+"%");row.addView(label,new LinearLayout.LayoutParams(dp(72),-2));
        android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(90);bar.setProgress(Math.max(0,Math.round(alpha.getAsInt()*100f/255f)-10));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){int percent=value+10;label.setText("투명도 "+percent+"%");if(user)set.accept(Math.round(percent*255f/100f));}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b){}});
        row.addView(bar,new LinearLayout.LayoutParams(0,dp(32),1));return row;
    }
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose,int size){return swatches(colors,current,choose,size,0);}
    /** @param more 0 = presets only, 1 = adds a rainbow chip that opens the free colour chooser, 2 = the same with an opacity slider */
    private LinearLayout swatches(int[] colors,java.util.function.IntSupplier current,java.util.function.IntConsumer choose,int size,int more){
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
            final Runnable chipLook=()->{int now=current.getAsInt();boolean custom=true;for(int c:colors)if(c==now){custom=false;break;}
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
        TextView chip=new TextView(this);chip.setText(label);chip.setTextSize(15);chip.setGravity(Gravity.CENTER);chip.setTypeface(Typeface.defaultFromStyle(typefaceStyle));
        Runnable paint=()->{chip.setBackground(round(flag[0]?ACCENT:0xFFF2F2F7,18));chip.setTextColor(flag[0]?Color.WHITE:NAVY);};
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
                t.add(new Tile("현재 문서 이름 변경",R.drawable.ic_text,()->{if(activeSession!=null)renameDocument(activeSession);else toast("문서를 먼저 여세요");}));
                break;
            case 1:
                t.add(new Tile("페이지 미리보기",R.drawable.ic_thumbnails,this::toggleSidebar).selected(sidebarVisible));
                t.add(new Tile("페이지로 이동",R.drawable.ic_page,this::goToPage));
                t.add(new Tile("현재 페이지 뒤에 추가",R.drawable.ic_note_add,()->choosePageToInsert(currentPage)));
                t.add(new Tile("페이지 삭제",R.drawable.ic_delete,()->confirmDeletePage(currentPage)).tint(0xFFFF3B30));
                t.add(new Tile("두 쪽 보기 · "+(twoPage?"켜짐":"꺼짐"),R.drawable.ic_thumbnails,this::toggleTwoPage).selected(twoPage));
                t.add(new Tile("전체 화면",R.drawable.ic_fullscreen,this::toggleFullscreen));
                t.add(new Tile("페이지 넘김 설정",R.drawable.ic_sliders,this::choosePageSwipeDirection));t.add(new Tile("넘김 효과",R.drawable.ic_sliders,this::choosePageAnimation));
                t.add(new Tile("읽기·페이지 넘김",R.drawable.ic_book,()->setInkMode(0)));
                break;
            case 2:
                t.add(new Tile("필기 모드",R.drawable.ic_ink,()->setWriteMode(true)));
                t.add(new Tile("올가미·영역 캡처",R.drawable.ic_lasso,this::startLasso));
                t.add(new Tile("텍스트 선택",R.drawable.ic_scan,this::startTextSelection));
                t.add(new Tile("타이핑",R.drawable.ic_text,this::toggleTyping));
                t.add(new Tile("사진·이미지",R.drawable.ic_image,this::pickImage));
                t.add(new Tile("스티커",R.drawable.ic_sticker,this::showStickerPicker));
                t.add(new Tile("동영상",R.drawable.ic_video,this::pickVideo));
                t.add(new Tile("하이퍼링크",R.drawable.ic_link,this::startHyperlink));
                t.add(new Tile("이미지 붙여넣기",R.drawable.ic_copy,this::pasteImage));
                t.add(new Tile("유튜브 링크",R.drawable.ic_video,this::askYoutube));
                t.add(new Tile("음성 녹음",R.drawable.ic_mic,this::startRecording));
                t.add(new Tile("메모 추가",R.drawable.ic_note_add,this::toggleMemoMode));
                break;
            case 3:
                t.add(new Tile("문서·필기 검색",R.drawable.ic_search,this::searchDocument));
                t.add(new Tile("듀얼 뷰 노트",R.drawable.ic_note_add,()->showStudy(false)));
                t.add(new Tile("발췌 바구니",R.drawable.ic_copy,()->showStudy(true)));
                t.add(new Tile("메모·하이라이트",R.drawable.ic_highlight,this::showMarkList));
                t.add(new Tile("번역 포스트잇",R.drawable.ic_translate,this::showTranslations));
                t.add(new Tile("책갈피",R.drawable.ic_star,this::showBookmarks));
                t.add(new Tile("개요 목록",R.drawable.ic_outline,this::showOutlineList));
                t.add(new Tile("개요 추가",R.drawable.ic_note_add,this::toggleOutlineMode));
                t.add(new Tile("글자 다시 인식",R.drawable.ic_scan,()->recognizePageText(true)));
                break;
            default:
                t.add(new Tile("PDF 내보내기",R.drawable.ic_folder_open,this::exportPdf));
                t.add(new Tile("노트·발췌 내보내기",R.drawable.ic_copy,this::exportStudy));
                t.add(new Tile("주석 백업",R.drawable.ic_copy,this::exportAnnotations));
                t.add(new Tile("주석 백업 복원",R.drawable.ic_undo,this::importSidecar));
                t.add(new Tile("원본 파일 내보내기",R.drawable.ic_folder_open,this::exportOriginal));
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
        sections.add(new Section("읽기 편의").add(new Tile("화면 켜 둠",R.drawable.ic_clock,()->{recentPrefs.edit().putBoolean("keep_awake",!awake).apply();applyKeepAwake();toast(!awake?"읽는 동안 화면이 꺼지지 않습니다":"화면 자동 꺼짐을 따릅니다");}).selected(awake)));
        sections.add(new Section("도움말").add(new Tile("사용법",R.drawable.ic_outline,this::showHelp)));
        showSheet("메뉴",sections);
    }
    private int widthIndex(){int best=0;for(int i=0;i<INK_WIDTHS.length;i++)if(Math.abs(INK_WIDTHS[i]-inkWidth)<Math.abs(INK_WIDTHS[best]-inkWidth))best=i;return best;}
    // ================================================================== lasso shape bar
    private void buildLassoBar(){
        lassoBar=new LinearLayout(this);lassoBar.setTag("lasso_bar");lassoBar.setGravity(Gravity.CENTER_VERTICAL);lassoBar.setPadding(dp(6),dp(4),dp(4),dp(4));
        lassoBar.setBackground(round(0xF2FFFFFF,24));lassoBar.setElevation(dp(6));lassoBar.setVisibility(View.GONE);
        int[] icons={R.drawable.ic_lasso,R.drawable.ic_rect,R.drawable.ic_circle};String[] names={"자유","네모","원"};
        for(int i=0;i<3;i++){
            final int shape=i;LinearLayout chip=new LinearLayout(this);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(dp(10),0,dp(12),0);chip.setTag("lasso_shape_"+i);chip.setContentDescription("올가미 "+names[i]);
            ImageView glyph=new ImageView(this);glyph.setImageResource(icons[i]);glyph.setColorFilter(NAVY);chip.addView(glyph,new LinearLayout.LayoutParams(dp(20),dp(20)));
            TextView label=new TextView(this);label.setText(names[i]);label.setTextSize(13);label.setTextColor(NAVY);label.setPadding(dp(5),0,0,0);chip.addView(label,new LinearLayout.LayoutParams(-2,-2));
            chip.setOnClickListener(v->chooseLassoShape(shape));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(36));p.setMargins(dp(2),0,dp(2),0);lassoBar.addView(chip,p);
        }
        lassoBar.addView(icon(R.drawable.ic_close,"올가미 종료",0xFF8E8E93,v->setInkMode(0)),new LinearLayout.LayoutParams(dp(40),dp(40)));
        updateLassoBar();
    }
    private void chooseLassoShape(int shape){
        lassoShape=shape;recentPrefs.edit().putInt("lasso_shape",shape).apply();
        pageView.setLassoShape(shape);syncOtherTools();updateLassoBar();
        toast(shape==PdfPageView.LASSO_RECT?"네모: 대각선으로 드래그하세요":shape==PdfPageView.LASSO_CIRCLE?"원: 중심에서 바깥쪽으로 드래그하세요":"자유: 원하는 영역을 둘러 그리세요");
    }
    private void updateLassoBar(){
        if(lassoBar==null||pageView==null)return;
        boolean on=pageView.isLassoMode();lassoBar.setVisibility(on?View.VISIBLE:View.GONE);if(!on)return;
        for(int i=0;i<3;i++){
            View chip=lassoBar.findViewWithTag("lasso_shape_"+i);if(!(chip instanceof LinearLayout))continue;
            boolean selected=i==lassoShape;LinearLayout group=(LinearLayout)chip;group.setBackground(round(selected?ACTIVE_BG:Color.TRANSPARENT,18));
            ((ImageView)group.getChildAt(0)).setColorFilter(selected?ACTIVE_FG:NAVY);((TextView)group.getChildAt(1)).setTextColor(selected?ACTIVE_FG:NAVY);
        }
    }
    private void toggleLasso(){
        if(renderer==null){toast("PDF를 먼저 여세요");return;}
        if(pageView.isLassoMode()){setInkMode(0);toast("올가미를 종료했습니다");}else startLasso();
    }
    private void startLasso(){
        if(renderer==null){toast("PDF를 먼저 여세요");return;}
        onSelectionAdjustStarted();highlightMode=memoMode=outlineMode=false;placementKind="";inkMode=0;
        pageView.setLassoShape(lassoShape);pageView.setLassoMode(true);updateToolStates();
        toast(lassoShape==PdfPageView.LASSO_RECT?"드래그해서 네모 영역을 지정하세요. 모양은 위쪽 막대에서 바꿀 수 있습니다.":lassoShape==PdfPageView.LASSO_CIRCLE?"중심에서 바깥쪽으로 드래그해 원형 영역을 지정하세요.":"손가락 또는 S펜으로 원하는 영역을 둘러 그리세요. 두 손가락으로 확대할 수 있습니다.");
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
        if(firstPageView!=null&&firstPageView.getVisibility()==View.VISIBLE&&firstPageView.getPageNumber()==page)return firstPageView;
        if(secondPageView!=null&&secondPageView.getVisibility()==View.VISIBLE&&secondPageView.getPageNumber()==page)return secondPageView;
        return null;
    }
    private void redrawPages(){if(firstPageView!=null)firstPageView.invalidate();if(secondPageView!=null)secondPageView.invalidate();}
    private AnnotationStore.PageElement elementAt(int page,float x,float y){
        if(store==null)return null;
        for(int i=store.elements.size()-1;i>=0;i--){AnnotationStore.PageElement e=store.elements.get(i);if(e.page==page&&"text".equals(e.kind)&&x>=e.left&&x<=e.right&&y>=e.top&&y<=e.bottom)return e;}
        return null;
    }
    private void loadTextStyle(AnnotationStore.PageElement e){
        e.font=recentPrefs.getString("text_font","sans");if(!AnnotationStore.PageElement.FONTS.contains(e.font))e.font="sans";
        e.bold=recentPrefs.getBoolean("text_bold",false);e.italic=recentPrefs.getBoolean("text_italic",false);
        e.color=recentPrefs.getInt("text_color",AnnotationStore.PageElement.DEFAULT_TEXT_COLOR);
        float size=recentPrefs.getFloat("text_size",AnnotationStore.PageElement.DEFAULT_TEXT_SIZE);e.textSize=size<.004f||size>.3f?AnnotationStore.PageElement.DEFAULT_TEXT_SIZE:size;
    }
    private void saveTextStyle(AnnotationStore.PageElement e){
        recentPrefs.edit().putString("text_font",e.font).putBoolean("text_bold",e.bold).putBoolean("text_italic",e.italic).putInt("text_color",e.color).putFloat("text_size",e.textSize).apply();
    }
    /** Resizes the box height so the whole text is visible with the element's own width, size and typeface. */
    private void fitTextElement(AnnotationStore.PageElement e){
        PdfPageView view=viewForPage(e.page);float aspect=view!=null?view.pageAspect():1.414f;
        float height=AnnotationPainter.fitHeight(e.text,e.right-e.left,e.textSize,aspect,AnnotationPainter.typeface(e.font,e.bold,e.italic));
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
        inlineEdit.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence q,int a,int b,int c){}public void onTextChanged(CharSequence q,int a,int b,int c){e.text=q.toString();}public void afterTextChanged(android.text.Editable q){}});
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
        if(Math.abs(inlineEdit.getTextSize()-px)>.4f){inlineEdit.setTextSize(TypedValue.COMPLEX_UNIT_PX,px);Paint.FontMetrics fm=inlineEdit.getPaint().getFontMetrics();inlineEdit.setLineSpacing(Math.max(0f,px*1.35f-(fm.descent-fm.ascent)),1f);}
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)inlineEdit.getLayoutParams();
        if(lp.leftMargin!=left||lp.topMargin!=top||lp.width!=width){lp.leftMargin=left;lp.topMargin=top;lp.width=width;inlineEdit.setLayoutParams(lp);}
        int editBottom=top+Math.max(dp(18),inlineEdit.getHeight());
        placeHandle(inlineMove,left-dp(8),top-dp(34));placeHandle(inlineDelete,left+width-dp(22),top-dp(34));placeHandle(inlineResize,left+width-dp(12),editBottom-dp(10));
        placeInlineBar(top,editBottom);
    }
    /** Keeps the toolbar clear of the text being typed: above the box first (the keyboard covers the lower part), else below, else at the top. */
    private void placeInlineBar(int editTop,int editBottom){
        if(inlineBar==null||viewportLayer==null||viewportLayer.getHeight()<=0)return;
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
        inlineEdit.setTypeface(AnnotationPainter.typeface(e.font,e.bold,e.italic));inlineEdit.setTextColor(e.color|0xFF000000);
        if(inlineSize!=null)inlineSize.setText(pointsOf(e)+"pt");positionInlineText();
    }
    private void changeInlineSize(int delta){
        if(inlineElement==null)return;int points=Math.max(8,Math.min(72,pointsOf(inlineElement)+delta));inlineElement.textSize=points/(float)TEXT_PAGE_POINTS;applyInlineStyle();
    }
    /** Slim one-row toolbar (Aa · B · I · size · delete · done); the font and colour rows open only when "Aa" is tapped. */
    private void buildInlineBar(AnnotationStore.PageElement e){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(6),dp(2),dp(6),dp(2));card.setTag("inline_style_bar");
        final LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setVisibility(View.GONE);
        LinearLayout faces=segmented(FONT_NAMES,()->Math.max(0,Arrays.asList(FONT_IDS).indexOf(e.font)),i->{e.font=FONT_IDS[i];applyInlineStyle();});faces.setTag("text_fonts");faces.setPadding(0,dp(2),0,dp(2));panel.addView(faces,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout palette=swatches(TEXT_COLORS,()->e.color|0xFF000000,c->{e.color=c|0xFF000000;applyInlineStyle();},26,1);palette.setTag("text_colors");palette.setPadding(0,dp(2),0,dp(2));panel.addView(palette,new LinearLayout.LayoutParams(-1,dp(34)));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        final boolean[] bold={e.bold},italic={e.italic};
        TextView style=stepButton("Aa","글꼴·색 펼치기");style.setTextSize(14);style.setTypeface(Typeface.DEFAULT_BOLD);style.setTag("text_style_toggle");
        style.setOnClickListener(v->{boolean open=panel.getVisibility()!=View.VISIBLE;panel.setVisibility(open?View.VISIBLE:View.GONE);style.setBackground(round(open?0xFFD6E6FF:0xFFF2F2F7,18));});
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(38),dp(32));sp.setMargins(dp(2),0,dp(6),0);row.addView(style,sp);
        TextView boldChip=toggleChip("B",Typeface.BOLD,bold,()->{e.bold=bold[0];applyInlineStyle();});boldChip.setTag("text_bold");TextView italicChip=toggleChip("I",Typeface.ITALIC,italic,()->{e.italic=italic[0];applyInlineStyle();});italicChip.setTag("text_italic");
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(32),dp(32));cp.setMargins(dp(2),0,dp(2),0);row.addView(boldChip,cp);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(32),dp(32));ip.setMargins(dp(2),0,dp(6),0);row.addView(italicChip,ip);
        TextView minus=stepButton("−","글자 작게");minus.setOnClickListener(v->changeInlineSize(-1));row.addView(minus,new LinearLayout.LayoutParams(dp(30),dp(32)));
        inlineSize=new TextView(this);inlineSize.setTag("text_size");inlineSize.setTextSize(12);inlineSize.setTextColor(NAVY);inlineSize.setGravity(Gravity.CENTER);inlineSize.setTypeface(Typeface.DEFAULT_BOLD);row.addView(inlineSize,new LinearLayout.LayoutParams(dp(40),dp(32)));
        TextView plus=stepButton("＋","글자 크게");plus.setOnClickListener(v->changeInlineSize(1));row.addView(plus,new LinearLayout.LayoutParams(dp(30),dp(32)));
        row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        ImageButton remove=icon(R.drawable.ic_delete,"글상자 삭제",0xFFFF3B30,v->deleteInlineText());remove.setPadding(dp(7),dp(7),dp(7),dp(7));row.addView(remove,new LinearLayout.LayoutParams(dp(34),dp(34)));
        ImageButton done=icon(R.drawable.ic_check,"입력 완료",Color.WHITE,v->commitInlineText());done.setTag("text_done");done.setBackground(round(ACCENT,17));done.setPadding(dp(7),dp(7),dp(7),dp(7));LinearLayout.LayoutParams dp2=new LinearLayout.LayoutParams(dp(34),dp(34));dp2.setMargins(dp(4),0,0,0);row.addView(done,dp2);
        card.addView(row,new LinearLayout.LayoutParams(-1,dp(38)));card.addView(panel,new LinearLayout.LayoutParams(-1,-2));
        inlineBar=card;card.setBackground(round(0xF2FFFFFF,20));card.setElevation(dp(6));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(dp(330),getResources().getDisplayMetrics().widthPixels-dp(16)),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.setMargins(dp(8),0,dp(8),0);viewportLayer.addView(inlineBar,lp);
    }
    private TextView stepButton(String label,String description){TextView b=new TextView(this);b.setText(label);b.setTextSize(18);b.setTextColor(NAVY);b.setGravity(Gravity.CENTER);b.setContentDescription(description);b.setBackground(round(0xFFF2F2F7,18));return b;}
    private void removeInlineViews(){
        AnnotationPainter.skip=null;
        if(inlineTracker!=null&&viewportLayer!=null)viewportLayer.getViewTreeObserver().removeOnPreDrawListener(inlineTracker);inlineTracker=null;
        for(View v:new View[]{inlineEdit,inlineMove,inlineResize,inlineDelete,inlineBar})if(v!=null)viewportLayer.removeView(v);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(inlineEdit!=null){InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(inlineEdit.getWindowToken(),0);}
        inlineEdit=null;inlineMove=inlineResize=inlineDelete=null;inlineBar=null;inlineSize=null;inlineElement=null;inlineStore=null;inlineView=null;
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
    private Dialog showActionSheet(String title,String[] labels,int checked,java.util.function.IntConsumer pick){
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setTag("action_sheet");root.setPadding(dp(10),0,dp(10),dp(12));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setBackground(round(Color.WHITE,14));
        if(title!=null){TextView t=new TextView(this);t.setText(title);t.setTextSize(13);t.setTextColor(0xFF8E8E93);t.setGravity(Gravity.CENTER);t.setPadding(dp(16),dp(14),dp(16),dp(12));list.addView(t,new LinearLayout.LayoutParams(-1,-2));}
        for(int i=0;i<labels.length;i++){
            final int index=i;
            if(i>0||title!=null){View line=new View(this);line.setBackgroundColor(0xFFE5E5EA);list.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2)));}
            TextView row=new TextView(this);row.setText(i==checked?Glyph.check(this,ACCENT,labels[i]):labels[i]);row.setTextSize(18);row.setTextColor(ACCENT);row.setGravity(Gravity.CENTER);if(i==checked)row.setTypeface(Typeface.DEFAULT_BOLD);row.setContentDescription(labels[i]);
            row.setOnClickListener(v->{dialog.dismiss();pick.accept(index);});list.addView(row,new LinearLayout.LayoutParams(-1,dp(54)));
        }
        root.addView(list,new LinearLayout.LayoutParams(-1,-2));
        TextView cancel=new TextView(this);cancel.setText("취소");cancel.setTextSize(18);cancel.setTextColor(ACCENT);cancel.setTypeface(Typeface.DEFAULT_BOLD);cancel.setGravity(Gravity.CENTER);cancel.setBackground(round(Color.WHITE,14));cancel.setOnClickListener(v->dialog.dismiss());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(54));cp.topMargin=dp(8);root.addView(cancel,cp);
        dialog.setContentView(root);dialog.setCanceledOnTouchOutside(true);dialog.show();
        Window window=dialog.getWindow();if(window!=null){window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(480)),ViewGroup.LayoutParams.WRAP_CONTENT);}
        return dialog;
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
    private TextView dialogButton(String label,int color,boolean bold,Runnable action){TextView b=new TextView(this);b.setText(label);b.setTextSize(16);b.setTextColor(color);if(bold)b.setTypeface(Typeface.DEFAULT_BOLD);b.setGravity(Gravity.CENTER);b.setPadding(dp(12),0,dp(12),0);b.setContentDescription(label);b.setOnClickListener(v->action.run());return b;}

    // ================================================================== side panel: search / page previews / outline / recordings
    private LinearLayout sidePanel,outlineList,recordingList;private FrameLayout sideContent;private TextView sideTitle;private ImageButton sideMore;private ScrollView outlineScroll,recordingScroll;
    private final ImageButton[] sideTabs=new ImageButton[4];private int panelTab=1;
    private static final String[] SIDE_TITLES={"검색","페이지 미리보기","개요","음성 녹음"};
    private static final int[] SIDE_ICONS={R.drawable.ic_search,R.drawable.ic_thumbnails,R.drawable.ic_outline,R.drawable.ic_mic};
    private void buildSidePanel(){
        sidePanel=new LinearLayout(this);sidePanel.setTag("side_panel");sidePanel.setOrientation(LinearLayout.VERTICAL);sidePanel.setBackgroundColor(0xFFF8F8F8);sidePanel.setVisibility(View.GONE);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(12),0,dp(0),0);
        sideTitle=new TextView(this);sideTitle.setTag("side_title");sideTitle.setTextSize(15);sideTitle.setTextColor(NAVY);sideTitle.setTypeface(Typeface.DEFAULT_BOLD);sideTitle.setSingleLine();sideTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);sideTitle.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);head.addView(sideTitle,new LinearLayout.LayoutParams(0,dp(48),1));
        sideMore=icon(R.drawable.ic_more_vert,"미리보기 메뉴",NAVY,v->showThumbnailMenu(v));sideMore.setTag("side_more");sideMore.setPadding(dp(7),dp(12),dp(7),dp(12));head.addView(sideMore,new LinearLayout.LayoutParams(dp(36),dp(48)));
        ImageButton closePanel=icon(R.drawable.ic_close,"패널 닫기",NAVY,v->closeSidePanel());closePanel.setPadding(dp(9),dp(12),dp(9),dp(12));head.addView(closePanel,new LinearLayout.LayoutParams(dp(38),dp(48)));
        sidePanel.addView(head,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(8),0,dp(8),dp(6));String[] names={"검색 탭","페이지 미리보기 탭","개요 탭","음성 녹음 탭"};
        for(int i=0;i<4;i++){final int tab=i;ImageButton b=icon(SIDE_ICONS[i],names[i],NAVY,v->selectPanelTab(tab));b.setPadding(dp(8),dp(8),dp(8),dp(8));b.setTag("side_tab:"+i);sideTabs[i]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(dp(2),0,dp(2),0);tabs.addView(b,lp);}
        sidePanel.addView(tabs,new LinearLayout.LayoutParams(-1,dp(46)));
        sideContent=new FrameLayout(this);sideContent.addView(searchPanel,new FrameLayout.LayoutParams(-1,-1));sideContent.addView(thumbnailPanel,new FrameLayout.LayoutParams(-1,-1));
        outlineList=new LinearLayout(this);outlineList.setOrientation(LinearLayout.VERTICAL);outlineList.setPadding(dp(10),dp(4),dp(10),dp(14));outlineScroll=new ScrollView(this);outlineScroll.addView(outlineList,new ScrollView.LayoutParams(-1,-2));sideContent.addView(outlineScroll,new FrameLayout.LayoutParams(-1,-1));
        recordingList=new LinearLayout(this);recordingList.setOrientation(LinearLayout.VERTICAL);recordingList.setPadding(dp(10),dp(4),dp(10),dp(14));recordingScroll=new ScrollView(this);recordingScroll.addView(recordingList,new ScrollView.LayoutParams(-1,-2));sideContent.addView(recordingScroll,new FrameLayout.LayoutParams(-1,-1));
        sidePanel.addView(sideContent,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void selectPanelTab(int tab){
        panelTab=tab;sidebarVisible=true;sidePanel.setVisibility(View.VISIBLE);
        searchPanel.setVisibility(tab==0?View.VISIBLE:View.GONE);thumbnailPanel.setVisibility(tab==1?View.VISIBLE:View.GONE);outlineScroll.setVisibility(tab==2?View.VISIBLE:View.GONE);recordingScroll.setVisibility(tab==3?View.VISIBLE:View.GONE);
        sideTitle.setText(tab==1&&!showAllThumbnails?"즐겨찾기 페이지":SIDE_TITLES[tab]);sideMore.setVisibility(tab==1?View.VISIBLE:View.GONE);
        for(int i=0;i<4;i++){boolean on=i==tab;sideTabs[i].setColorFilter(on?ACTIVE_FG:NAVY);sideTabs[i].setBackground(on?round(ACTIVE_BG,14):null);}
        LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)sidePanel.getLayoutParams();int width=sidePanelWidth();if(lp.width!=width){lp.width=width;sidePanel.setLayoutParams(lp);}
        if(tab==0){searchInput.requestFocus();InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(searchInput,InputMethodManager.SHOW_IMPLICIT);}else hideKeyboard();
        rebuildThumbnails();applySearchHighlights();
    }
    /** One width for every tab so the panel never jumps when switching between search, previews, outline and recordings. */
    private int sidePanelWidth(){return Math.min(dp(190),Math.round(getResources().getDisplayMetrics().widthPixels*.42f));}
    private void closeSidePanel(){sidebarVisible=false;sidePanel.setVisibility(View.GONE);closeSearch();hideKeyboard();applySearchHighlights();}
    /** Icon-only floating menu for the preview panel: favorites only, all pages, add page, delete page. */
    private void showThumbnailMenu(View anchor){
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(6),dp(6),dp(6),dp(6));column.setBackground(round(Color.WHITE,18));column.setTag("thumb_menu");column.setElevation(dp(10));
        final PopupWindow[] window=new PopupWindow[1];
        Object[][] entries={{R.drawable.ic_star,"즐겨찾기 페이지만",!showAllThumbnails,NAVY},{R.drawable.ic_thumbnails,"전체 페이지",showAllThumbnails,NAVY},{R.drawable.ic_note_add,"페이지 추가",false,NAVY},{R.drawable.ic_delete,"페이지 삭제",false,0xFFFF3B30}};
        for(int i=0;i<entries.length;i++){
            final int id=i;boolean on=(Boolean)entries[i][2];
            ImageButton button=icon((Integer)entries[i][0],(String)entries[i][1],on?ACTIVE_FG:(Integer)entries[i][3],v->{window[0].dismiss();if(id<2){showAllThumbnails=id==1;recentPrefs.edit().putBoolean("thumb_all",showAllThumbnails).apply();selectPanelTab(1);}else if(id==2)chooseAddedPage();else confirmDeletePage(currentPage);});
            button.setBackground(on?round(ACTIVE_BG,14):null);button.setPadding(dp(10),dp(10),dp(10),dp(10));column.addView(button,new LinearLayout.LayoutParams(dp(46),dp(46)));
        }
        window[0]=new PopupWindow(column,-2,-2,true);window[0].setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));window[0].setElevation(dp(10));window[0].showAsDropDown(anchor,-dp(8),-dp(4),Gravity.END);
    }
    private TextView pill(String text,String description,int background,int foreground,View.OnClickListener action){TextView b=new TextView(this);b.setText(text);b.setTextSize(14);b.setTextColor(foreground);b.setTypeface(Typeface.DEFAULT_BOLD);b.setGravity(Gravity.CENTER);b.setContentDescription(description);b.setBackground(round(background,18));b.setOnClickListener(action);return b;}
    /** Lets a list row be swiped away (either direction) to delete it; taps and vertical scrolling keep working. */
    private void swipeToDelete(View row,Runnable delete){
        final float[] start=new float[2];final boolean[] dragging={false};final int slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        row.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:start[0]=e.getRawX();start[1]=e.getRawY();dragging[0]=false;return false;
                case MotionEvent.ACTION_MOVE:{float dx=e.getRawX()-start[0],dy=e.getRawY()-start[1];
                    if(!dragging[0]&&Math.abs(dx)>slop*1.5f&&Math.abs(dx)>Math.abs(dy)*1.4f){dragging[0]=true;v.setPressed(false);if(v.getParent()!=null)v.getParent().requestDisallowInterceptTouchEvent(true);}
                    if(dragging[0]){v.setTranslationX(dx);v.setAlpha(Math.max(.25f,1f-Math.abs(dx)/Math.max(1,v.getWidth())));return true;}return false;}
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                    if(dragging[0]){dragging[0]=false;float dx=v.getTranslationX();boolean gone=e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(dx)>v.getWidth()*.4f;
                        if(gone)v.animate().translationX(Math.signum(dx)*v.getWidth()).alpha(0f).setDuration(140).withEndAction(delete).start();
                        else v.animate().translationX(0).alpha(1f).setDuration(160).start();return true;}
                    return false;
            }
            return false;
        });
    }
    private void rebuildOutlinePanel(){rebuildOutlineItems();appendMarkList();}
    /** Lists every highlight and sticky memo of the document under the outline so any annotation is one tap away. */
    private void appendMarkList(){
        if(store==null)return;List<AnnotationStore.Mark> listed=new ArrayList<>();for(AnnotationStore.Mark m:store.marks)if(m.noteOnly||(m.note!=null&&!m.note.trim().isEmpty()))listed.add(m);if(listed.isEmpty())return;
        TextView heading=new TextView(this);heading.setText("메모 "+listed.size());heading.setTextSize(12);heading.setTextColor(0xFF8E8E93);heading.setPadding(dp(6),dp(14),0,dp(6));outlineList.addView(heading);
        List<AnnotationStore.Mark> marks=new ArrayList<>(listed);marks.sort(Comparator.comparingInt((AnnotationStore.Mark m)->m.page).thenComparingDouble(m->m.top));
        for(AnnotationStore.Mark mark:marks){
            LinearLayout row=new LinearLayout(this);row.setTag("mark_item");row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(round(Color.WHITE,12));
            View dot=new View(this);GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);shape.setColor(mark.noteOnly?mark.paper:(mark.color|0xFF000000));shape.setStroke(dp(1),0x22000000);dot.setBackground(shape);LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(dp(14),dp(14));dp1.rightMargin=dp(10);row.addView(dot,dp1);
            String note=mark.note==null?"":mark.note.trim();TextView text=new TextView(this);text.setText((mark.noteOnly?"메모":"하이라이트")+" (p"+(mark.page+1)+")"+(note.isEmpty()?"":"\n"+note));text.setTextSize(12);text.setTextColor(NAVY);text.setMaxLines(3);text.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            row.setOnClickListener(v->{showPage(mark.page);pageView.post(()->pageView.focusOnPoint((mark.left+mark.right)/2,(mark.top+mark.bottom)/2));});swipeToDelete(row,()->{store.marks.remove(mark);store.save();redrawPages();rebuildOutlinePanel();toast("삭제했습니다");});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);outlineList.addView(row,params);
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
            row.addView(icon(R.drawable.ic_more_vert,"개요 관리",NAVY,v->showOutlineItem(item)),new LinearLayout.LayoutParams(dp(44),dp(44)));
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
            row.addView(icon(R.drawable.ic_delete,"녹음 삭제",0xFFFF3B30,v->deleteRecording(clip)),new LinearLayout.LayoutParams(dp(44),dp(44)));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);recordingList.addView(row,params);
        }
    }

    // ================================================================== voice recording attached to a page
    private MediaRecorder recorder;private File recordingFile;private long recordingStarted;private int recordingPage;private AnnotationStore recordingStore;private LinearLayout recorderBar;private TextView recorderTime;private static final int REQUEST_RECORD=31;
    private static String clock(long seconds){seconds=Math.max(0,seconds);return (seconds/60)+":"+(seconds%60<10?"0":"")+(seconds%60);}
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
        row.addView(searchInput,new LinearLayout.LayoutParams(0,dp(40),1));
        row.addView(icon(R.drawable.ic_chevron_up,"이전 결과",NAVY,v->stepSearch(-1)),new LinearLayout.LayoutParams(dp(40),dp(40)));
        row.addView(icon(R.drawable.ic_chevron_down,"다음 결과",NAVY,v->stepSearch(1)),new LinearLayout.LayoutParams(dp(40),dp(40)));
        row.addView(icon(R.drawable.ic_close,"검색 닫기",0xFF8E8E93,v->closeSearch()),new LinearLayout.LayoutParams(dp(40),dp(40)));
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
        for(PdfPageView view:new PdfPageView[]{firstPageView,secondPageView}){
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
        String[] labels={"★ 즐겨찾기","전체"};
        for(int i=0;i<2;i++){
            final boolean all=i==1;boolean on=all==showAllThumbnails;
            TextView chip=new TextView(this);chip.setText(labels[i]);chip.setTextSize(10);chip.setSingleLine();chip.setGravity(Gravity.CENTER);chip.setBackground(round(on?ACCENT:0xFFE5E5EA,14));chip.setTextColor(on?Color.WHITE:NAVY);chip.setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
            chip.setContentDescription(all?"전체 페이지 미리보기":"즐겨찾기한 페이지만 미리보기");
            chip.setOnClickListener(v->{if(showAllThumbnails==all)return;showAllThumbnails=all;recentPrefs.edit().putBoolean("thumb_all",all).apply();rebuildThumbnails();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(30),all?2f:3f);p.setMargins(dp(2),0,dp(2),0);row.addView(chip,p);
        }
        return row;
    }
    private List<Integer> thumbnailPages(){
        List<Integer> pages=new ArrayList<>();int total=renderer==null?0:renderer.getPageCount();
        if(showAllThumbnails){for(int i=0;i<total;i++)pages.add(i);}
        else if(store!=null){for(Integer page:store.bookmarks)if(page!=null&&page>=0&&page<total)pages.add(page);Collections.sort(pages);}
        return pages;
    }
    private void rebuildThumbnails(){
        if(!sidebarVisible)return;if(panelTab==2){rebuildOutlinePanel();return;}if(panelTab==3){rebuildRecordings();return;}if(panelTab!=1)return;final int generation=++thumbnailGeneration;thumbnailList.removeAllViews();if(renderer==null)return;
        final List<Integer> pages=thumbnailPages();
        if(pages.isEmpty()){
            TextView empty=new TextView(this);empty.setTag("thumb_empty");empty.setText("즐겨찾기한 페이지가 없습니다.\n\n아래쪽 ★를 누르면\n이곳에 미리보기가 나타납니다.");empty.setTextSize(12);empty.setTextColor(0xFF8E8E93);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(4),dp(18),dp(4),dp(8));
            thumbnailList.addView(empty,new LinearLayout.LayoutParams(-1,-2));return;
        }
        for(int i=0;i<pages.size();i++){final int page=pages.get(i);LinearLayout item=new LinearLayout(this);item.setTag(page);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setPadding(dp(4),dp(5),dp(4),dp(7));ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setAdjustViewBounds(true);preview.setBackgroundColor(Color.WHITE);preview.setElevation(dp(1));item.addView(preview,new LinearLayout.LayoutParams(sidePanelWidth()-dp(36),Math.round((sidePanelWidth()-dp(36))*thumbnailAspect())));TextView number=new TextView(this);number.setText(String.valueOf(page+1));number.setGravity(Gravity.CENTER);number.setTextSize(12);number.setTextColor(0xFF8E8E93);LinearLayout numberRow=new LinearLayout(this);numberRow.setGravity(Gravity.CENTER_VERTICAL);numberRow.addView(number,new LinearLayout.LayoutParams(0,dp(24),1));TextView pageMore=new TextView(this);pageMore.setText("⋮");pageMore.setTextSize(15);pageMore.setTextColor(0xFF8E8E93);pageMore.setGravity(Gravity.CENTER);pageMore.setContentDescription("페이지 "+(page+1)+" 메뉴");pageMore.setOnClickListener(v->showPageMenu(page));numberRow.addView(pageMore,new LinearLayout.LayoutParams(dp(28),dp(24)));item.addView(numberRow,new LinearLayout.LayoutParams(-1,dp(24)));item.setOnClickListener(v->showPage(page));item.setOnLongClickListener(v->{showPageMenu(page);return true;});thumbnailList.addView(item,new LinearLayout.LayoutParams(-1,-2));}
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
         "문서함|상단 왼쪽 폴더 버튼에서 PDF·노트·Office 문서를 폴더별로 관리합니다. 새 문서는 폴더 버튼 또는 탭 줄의 + 버튼으로 추가합니다.",
         "여러 문서|상단 탭으로 문서를 전환하고 × 로 닫습니다. 기록은 문서마다 따로 저장됩니다."},
        {"2. 화면 구성",
         "상단 줄|문서함, 문서 이름, 페이지 미리보기, 검색, 전체 화면, 더보기(⋮) 메뉴가 있습니다.",
         "하단 도구 줄|읽기 · 펜 · 하이라이트 · 지우개 · 올가미 · 텍스트 · 메모 · 삽입 · 실행 취소 · 다시 실행 순서입니다. 선택한 도구는 배경이 진하게 표시되고, 선택된 도구를 한 번 더 누르면 굵기·색 같은 세부 설정이 열립니다.",
         "왼쪽 패널|검색 · 페이지 미리보기 · 개요 · 음성 녹음 탭이 있습니다. 개요 아이콘으로 열고 × 로 닫습니다.",
         "반투명 화살표|본문 양옆의 화살표를 누르면 이전·다음 페이지로 이동합니다."},
        {"3. 읽기와 이동",
         "페이지 넘기기|본문을 좌우로 쓸어 넘깁니다. 손가락을 따라 책장이 접히며, 아래쪽을 잡으면 아래 모서리부터, 위쪽을 잡으면 위쪽부터 넘어갑니다. 더보기 메뉴의 ‘넘김 효과’에서 책장 넘김 · 슬라이드 · 효과 없음 중에서 고를 수 있습니다.",
         "확대·이동|두 손가락으로 확대하고, 확대한 상태에서는 드래그로 화면을 옮깁니다. 확대 중에는 화면 가장자리에서 쓸어야 페이지가 넘어갑니다.",
         "전체 화면|상단의 전체 화면 버튼을 누르면 메뉴가 숨겨집니다. 화면 아래에서 위로 쓸어올리면 도구 모음이 다시 나타납니다.",
         "검은 문서 배경|더보기 메뉴에서 켜면 종이를 검게, 글자는 밝게 표시합니다. 어두운 색 필기는 자동으로 밝게 보정됩니다.",
         "두 쪽 보기|가로로 넓은 화면(태블릿·폴드)에서 두 페이지를 나란히 봅니다."},
        {"4. 텍스트 선택과 단어 찾기",
         "선택하기|단어를 길게 누른 뒤 드래그해서 범위를 정합니다.",
         "선택 팝업|빈 곳을 길게 눌렀을 때와 같은 모양의 메뉴가 열립니다. 위쪽에 하이라이트 · 복사 · 번역 · 읽어주기 · 단어장 · 개요 · 메모 · 발췌 · 링크, 아래쪽에 사진·스티커·도형 같은 삽입 항목이 함께 나옵니다.",
         "단어장 연결|‘단어장’을 누르면 단어가 복사되고 영어 스터디 앱(github.com/hdlee73/english_study)이 열려 사전 탭에서 자동으로 검색됩니다. 영어 스터디 앱은 사전 탭이 열려 있을 때 복사된 단어를 검색하며, 앱이 없으면 설치 안내가 나옵니다. 돌아올 때는 최근 앱 화면이나 뒤로 가기를 사용합니다."},
        {"5. 필기 (펜)",
         "펜 선택|하단의 연필 아이콘을 눌러 필기 모드로 들어갑니다. S펜은 바로 쓰이고, 손가락 필기는 펜 메뉴의 ‘손가락 필기’를 켜야 합니다.",
         "펜 종류|펜 메뉴에서 볼펜 · 연필 · 만년필 · 붓 · 사인펜을 고릅니다. 연필은 가늘고 살짝 흐리며, 만년필은 펜촉 각도에 따라 굵기가 변하고, 붓은 시작과 끝이 가늘어지며, 사인펜은 일정한 굵기로 쓰입니다.",
         "굵기·색·투명도|굵기는 얇게~최대 4단계, 색은 기본 팔레트 또는 무지개 칩으로 원하는 색을 만들고, 투명도 막대로 흐리게 할 수 있습니다.",
         "직선|펜 메뉴의 ‘직선’을 켜면 시작점과 끝점을 잇는 반듯한 선을 긋습니다.",
         "지우개|지우개 아이콘으로 필기와 하이라이트를 지웁니다. 지울 부분을 문지르거나 눌러서 한 획(하이라이트는 한 덩어리)씩 지워집니다. 실행 취소·다시 실행도 사용할 수 있습니다.",
         "올가미|영역을 그려 필기를 선택하고 옮기거나 지웁니다. 올가미 모양은 도구에서 바꿀 수 있습니다."},
        {"6. 하이라이트",
         "만드는 법|하이라이트 아이콘을 켜고 글자를 드래그하거나, 글자를 선택한 뒤 팝업의 ‘하이라이트’를 누릅니다.",
         "색·투명도|하이라이트 아이콘을 한 번 더 누르면 색을 고를 수 있고, 무지개 칩에서 투명도까지 조절합니다.",
         "삭제|지우개로 문지르면 지워집니다. 하이라이트는 개요 목록에 나타나지 않습니다(메모가 붙은 것만 ‘메모’ 목록에 표시됩니다)."},
        {"7. 텍스트 상자",
         "넣기|하단의 T 아이콘을 누르고 문서를 탭하면 입력할 수 있습니다. 입력 중에는 글꼴 · 굵게/기울임 · 크기 · 색을 바꾸는 카드가 글상자 위나 아래에 나타나 입력 내용을 가리지 않습니다.",
         "이동·크기·삭제|입력 중 상자 위의 핸들로 이동하고, 모서리 핸들로 너비를 조절하며, 빨간 휴지통 또는 상자 위의 × 로 삭제합니다. ✓ 버튼으로 입력을 마칩니다."},
        {"8. 메모 포스트잇",
         "만들기|하단의 메모 아이콘을 누르고 문서를 탭해 내용을 입력합니다. 글자를 선택한 뒤 팝업의 ‘메모’로도 만들 수 있습니다.",
         "크기 조절|메모를 한 번 탭하면 점선 테두리와 오른쪽 아래 둥근 핸들이 나타납니다. 핸들을 끌어 가로·세로 크기를 자유롭게 바꿉니다. 이미 선택된 메모를 다시 탭하면 편집 창이 열립니다.",
         "편집 창|글자 크기(− ＋)는 메모 안 글자의 크기이고, 메모 상자 크기(작게·보통·크게)는 상자의 기본 크기입니다. 상자 크기를 고르면 직접 조절한 크기는 초기화됩니다. 메모 색은 기본 색, 무지개 칩, 투명도로 정합니다.",
         "숨기기·최소화|메모와 번역 포스트잇은 펼치기 · 최소화 · 숨기기로 관리합니다."},
        {"9. 삽입: 사진 · 스티커 · 도형 · 표 · 링크",
         "삽입 메뉴|하단의 + 상자 아이콘을 누르거나 문서의 빈 곳을 길게 눌러 열고, 넣을 종류를 고릅니다. 사진·동영상·유튜브 주소는 끌어다 놓거나 붙여넣기(Ctrl+V)도 됩니다.",
         "선택·이동·크기|넣은 개체를 한 번 탭하면 테두리와 모서리 핸들이 보입니다. 안쪽을 끌어 옮기고 모서리를 끌어 크기를 바꿉니다.",
         "회전|사진·스티커·도형·표는 선택하면 위쪽에 ↻ 핸들이 나타납니다. 끌면 돌아가고 15° 단위 근처에서 자석처럼 맞춰집니다. 도형은 모양 수정 창의 ‘회전’ 막대로 각도를 정할 수도 있습니다.",
         "도형·표|선 색, 채우기 색, 선 굵기를 정하고, 색마다 무지개 칩으로 원하는 색과 투명도를 고릅니다. 표는 행·열 수, 머리글 색, 칸 내용을 편집할 수 있습니다.",
         "하이퍼링크|글자를 선택한 뒤 팝업의 ‘링크’를 눌러 웹 주소, 현재 문서의 다른 페이지, 다른 문서로 연결합니다. 링크 글자는 파란 밑줄과 작은 화살표 배지로 표시되고, 탭하면 이동합니다."},
        {"10. 개요와 북마크",
         "개요 추가|개요 패널의 ‘＋ 개요 추가’를 누르고 문서의 원하는 위치를 탭한 뒤 제목을 입력합니다. 목록에는 ‘제목 (p19)’ 형식으로 표시되고 탭하면 그 위치로 이동합니다.",
         "관리|목록을 옆으로 밀면 삭제되고, ⋮ 버튼으로 이름 변경·삭제를 할 수 있습니다. 즐겨찾기(별)는 현재 페이지를 표시합니다."},
        {"11. 새 노트와 서식",
         "새 노트|문서함에서 새 노트를 만들면 종이 서식을 고릅니다. 백지 · 줄노트(보통·좁게·넓게) · 모눈종이 · 리걸노트 · 점 격자 · 코넬 노트 · 오선지가 있고, 종이 색도 고를 수 있습니다.",
         "내 서식|‘내 PDF·이미지 서식’을 고르면 가지고 있는 PDF의 첫 페이지나 이미지를 모든 페이지의 배경으로 씁니다.",
         "페이지 추가|노트의 마지막 장에서 다음으로 넘기면 같은 서식의 새 페이지가 붙습니다."},
        {"12. 음성 녹음 · 검색 · 번역",
         "음성 녹음|개요 패널의 마이크 탭에서 녹음하면 현재 페이지에 ‘▶ 녹음’ 표시가 붙고, 탭하면 재생합니다.",
         "검색|돋보기 아이콘으로 본문 글자를 찾고, 손글씨 필기도 검색됩니다.",
         "번역·읽어주기|글자를 선택한 뒤 팝업에서 번역 또는 읽어주기를 고릅니다."},
        {"13. 문서 변환 · 내보내기",
         "Office·한글 문서|HWP · HWPX · DOC · DOCX · PPT · PPTX · XLS · XLSX는 PDF로 변환해 문서함에 가져와 엽니다. 서식은 변환 엔진과 글꼴에 따라 달라질 수 있고, HWP·DOC의 본문 미리보기는 글자만 표시합니다.",
         "내보내기·백업|더보기 메뉴에서 기록이 포함된 PDF를 내보내거나 기록을 파일로 백업·복원합니다."}};
    private void showHelp(){
        final Dialog dialog=new Dialog(this,R.style.SheetDialog);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(round(Color.WHITE,20));card.setPadding(dp(22),dp(20),dp(22),dp(6));
        TextView title=new TextView(this);title.setText("PDF Note 사용법");title.setTextSize(21);title.setTextColor(NAVY);title.setTypeface(Typeface.DEFAULT_BOLD);title.setGravity(Gravity.START);card.addView(title,new LinearLayout.LayoutParams(-1,-2));
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
    @Override protected void onStop(){commitInlineText();onSelectionAdjustStarted();saveSessionState();super.onStop();}
    @Override protected void onDestroy(){stopRecording(true);if(libraryDialog!=null)libraryDialog.dismiss();if(searchCanceled!=null)searchCanceled.set(true);if(hwpConversion!=null)hwpConversion.cancel();saveSessionState();++ocrGeneration;if(speech!=null){speech.stop();speech.shutdown();speech=null;}if(latinRecognizer!=null)latinRecognizer.close();if(koreanRecognizer!=null)koreanRecognizer.close();closeAllDocuments();super.onDestroy();}
}
