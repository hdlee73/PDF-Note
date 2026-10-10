package com.hdlee.pdfnote;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import android.graphics.pdf.PdfRenderer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=ReadingToolbarTest.RendererShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InlineTextTest {
    private MainActivity activity;private AnnotationStore store;private View root;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        store=new AnnotationStore(activity);store.open(Uri.parse("content://inline/"+UUID.randomUUID()));
        set("store",store);set("renderer",new PdfRenderer(null));
        root=field("root");root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,360,720);
        PdfPageView page=field("firstPageView");page.showPage(Bitmap.createBitmap(600,850,Bitmap.Config.ARGB_8888),0,store.marks,store.strokes,store.translations);page.setAnnotationStore(store);
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,360,720);
    }
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(activity);}
    private void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private void invokeInt(String name,int value)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,int.class);m.setAccessible(true);m.invoke(activity,value);}
    private void invoke(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private static boolean hasText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),text))return true;return false;}
    private View byTag(String tag){return root.findViewWithTag(tag);}
    private static View byDescription(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=byDescription(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private EditText startTyping(float x,float y)throws Exception{invoke("toggleTyping");activity.onMemoPointRequested(0,x,y);EditText edit=(EditText)byTag("inline_text");assertNotNull("입력창이 페이지 위에 바로 나타나야 합니다",edit);return edit;}

    @Test public void tappingThePageTypesRightThereWithoutADialog()throws Exception{
        EditText edit=startTyping(.2f,.3f);assertNull("별도 입력 대화상자는 열리지 않습니다",ShadowDialog.getLatestDialog());
        assertNotNull(byTag("inline_style_bar"));assertTrue(edit.getParent()==field("viewportLayer"));
        edit.setText("안녕하세요\n두 번째 줄");byTag("text_done").performClick();
        assertNull(byTag("inline_text"));assertNull(byTag("inline_style_bar"));assertEquals(1,store.elements.size());
        AnnotationStore.PageElement e=store.elements.get(0);assertEquals("안녕하세요\n두 번째 줄",e.text);assertEquals(.2f,e.left,.001f);assertEquals(.3f,e.top,.001f);assertTrue(e.bottom>e.top);assertTrue(e.right>e.left);assertEquals("sans",e.font);
        assertTrue("완료 후에도 텍스트 모드는 유지",(Boolean)field("memoMode"));
    }
    @Test public void formatBarHidesWhileTypingAndReturnsOnTouch()throws Exception{
        EditText edit=startTyping(.2f,.3f);View bar=(View)byTag("inline_style_bar").getParent();
        assertEquals("입력 전에는 서식 메뉴가 보입니다",View.VISIBLE,bar.getVisibility());
        edit.setText("입력 시작");assertEquals("입력을 시작하면 서식 메뉴가 사라집니다",View.GONE,bar.getVisibility());
        long now=android.os.SystemClock.uptimeMillis();edit.dispatchTouchEvent(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_UP,5f,5f,0));
        assertEquals("글상자를 다시 누르면 서식 메뉴가 나타납니다",View.VISIBLE,bar.getVisibility());
        byTag("text_done").performClick();
    }
    @Test public void styleBarChangesFontBoldItalicSizeAndColor()throws Exception{
        EditText edit=startTyping(.1f,.1f);edit.setText("서식");
        int before=Math.round(AnnotationStore.PageElement.DEFAULT_TEXT_SIZE*595);
        byTag("text_bold").performClick();byTag("text_italic").performClick();
        View bigger=byDescription(root,"글자 크게");bigger.performClick();bigger.performClick();bigger.performClick();
        assertTrue(byTag("text_fonts") instanceof TextView);java.lang.reflect.Method pick=activity.getClass().getDeclaredMethod("setInlineFont",String.class);pick.setAccessible(true);pick.invoke(activity,"serif");
        View color=byDescription(root,"색상 6");color.performClick();
        assertEquals((before+3)+"pt",((TextView)byTag("text_size")).getText().toString());
        byTag("text_done").performClick();
        AnnotationStore.PageElement e=store.elements.get(0);assertTrue(e.bold);assertTrue(e.italic);assertEquals("serif",e.font);assertEquals((before+3)/595f,e.textSize,.0005f);assertEquals(0xFFFF3B30,e.color);
        activity.onMemoPointRequested(0,.1f,.6f);EditText next=(EditText)byTag("inline_text");assertNotNull(next);byTag("text_done").performClick();assertEquals("빈 상자는 저장하지 않습니다",1,store.elements.size());
        activity.onMemoPointRequested(0,.1f,.6f);assertEquals("마지막 서식이 이어집니다","serif",(String)field("inlineElement").getClass().getDeclaredField("font").get(field("inlineElement")));
    }
    @Test public void tappingAnExistingBoxEditsItInPlaceAndEmptyingDeletesIt()throws Exception{
        AnnotationStore.PageElement e=new AnnotationStore.PageElement();e.page=0;e.text="원래 글";e.left=.1f;e.top=.1f;e.right=.6f;e.bottom=.2f;store.elements.add(e);
        activity.onElementTapped(e);EditText edit=(EditText)byTag("inline_text");assertNotNull(edit);assertEquals("원래 글",edit.getText().toString());assertNull(ShadowDialog.getLatestDialog());
        edit.setText("고친 글");byTag("text_done").performClick();assertEquals(1,store.elements.size());assertSame(e,store.elements.get(0));assertEquals("고친 글",e.text);
        activity.onElementTapped(e);((EditText)byTag("inline_text")).setText("   ");byTag("text_done").performClick();assertTrue(store.elements.isEmpty());
    }
    @Test public void deleteButtonRemovesTheBoxAndBackKeyFinishesEditing()throws Exception{
        AnnotationStore.PageElement e=new AnnotationStore.PageElement();e.page=0;e.text="지울 글";e.left=.1f;e.top=.4f;e.right=.6f;e.bottom=.5f;store.elements.add(e);
        activity.onElementTapped(e);byDescription(root,"글상자 삭제").performClick();assertTrue(store.elements.isEmpty());assertNull(byTag("inline_text"));
        EditText edit=startTyping(.3f,.3f);edit.setText("뒤로 가기로 저장");activity.onBackPressed();assertNull(byTag("inline_text"));assertEquals(1,store.elements.size());assertEquals("뒤로 가기로 저장",store.elements.get(0).text);
    }
    @Test public void deleteHandleSitsOnTheBoxAndRemovesIt()throws Exception{
        AnnotationStore.PageElement e=new AnnotationStore.PageElement();e.page=0;e.text="지울 글";e.left=.1f;e.top=.4f;e.right=.6f;e.bottom=.5f;store.elements.add(e);
        activity.onElementTapped(e);View handle=byTag("inline_delete");assertNotNull("글상자 모서리에 삭제 버튼이 있습니다",handle);assertEquals("글상자 삭제",handle.getContentDescription().toString());
        handle.performClick();assertTrue(store.elements.isEmpty());assertNull(byTag("inline_text"));assertNull(byTag("inline_delete"));
    }
    @Test public void sidePanelHostsSearchPagesOutlineAndRecordings()throws Exception{
        View side=byTag("side_panel");assertEquals(View.GONE,side.getVisibility());
        byDescription(root,"페이지 목록").performClick();assertEquals(View.VISIBLE,side.getVisibility());assertTrue(((TextView)byTag("side_title")).getText().toString().endsWith("미리보기"));assertEquals(View.VISIBLE,byTag("side_more").getVisibility());
        byTag("side_tab:2").performClick();assertNotNull(byTag("outline_add"));assertEquals(View.GONE,byTag("side_more").getVisibility());assertTrue(((TextView)byTag("side_title")).getText().toString().endsWith("개요"));
        byTag("side_tab:3").performClick();assertNotNull(byTag("record_button"));assertTrue(((TextView)byTag("side_title")).getText().toString().endsWith("음성 녹음"));
        byTag("side_tab:0").performClick();assertEquals(View.VISIBLE,byTag("search_panel").getVisibility());assertNotNull(byTag("search_input"));
        byDescription(root,"검색 닫기").performClick();assertEquals(View.GONE,byTag("search_panel").getVisibility());assertEquals("검색을 닫아도 패널은 남습니다",View.VISIBLE,side.getVisibility());
        byDescription(root,"패널 닫기").performClick();assertEquals(View.GONE,side.getVisibility());
    }
    @Test public void audioNoteTapsOpenAPlayerAndListedRecordingsCanBeDeleted()throws Exception{
        AnnotationStore.PageElement clip=new AnnotationStore.PageElement();clip.page=0;clip.kind="audio";clip.asset=UUID.randomUUID()+".m4a";clip.text="0:12";clip.left=.04f;clip.top=.03f;clip.right=.42f;clip.bottom=.075f;store.elements.add(clip);
        activity.onElementTapped(clip);AlertDialog player=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotNull(player);assertEquals("녹음 파일이 없으면 안내 대화상자가 열립니다","녹음",player.getTitleText().toString());player.dismiss();
        byDescription(root,"페이지 목록").performClick();byTag("side_tab:3").performClick();assertNotNull("첨부한 녹음이 목록에 보입니다",byTag("recording_item"));
        byDescription(root,"녹음 관리").performClick();java.lang.reflect.Method del=activity.getClass().getDeclaredMethod("deleteRecording",AnnotationStore.PageElement.class);del.setAccessible(true);del.invoke(activity,clip);assertTrue(store.elements.isEmpty());assertNull(byTag("recording_item"));
    }
    @Test public void headerTitleRenamesAndLassoIsOnTheMainToolbar()throws Exception{
        View title=byTag("document_title");assertNotNull(title);assertTrue(title.hasOnClickListeners());
        View bar=byTag("reading_toolbar");View lasso=byDescription(bar,"올가미 선택");assertNotNull("올가미는 메인 하단 도구막대에 있습니다",lasso);
        lasso.performClick();PdfPageView page=field("pageView");assertTrue(page.isLassoMode());assertNull("올가미 모양은 막대가 아니라 떠 있는 메뉴에서 고릅니다",byTag("lasso_bar"));
        lasso.performClick();assertTrue("한 번 더 누르면 모양 메뉴가 열리고 올가미는 유지됩니다",page.isLassoMode());
        invokeInt("chooseLassoShape",PdfPageView.LASSO_RECT);assertEquals(PdfPageView.LASSO_RECT,page.getLassoShape());invokeInt("chooseLassoShape",PdfPageView.LASSO_CIRCLE);assertEquals(PdfPageView.LASSO_CIRCLE,page.getLassoShape());
        invokeInt("setInkMode",0);assertFalse(page.isLassoMode());
    }
}
