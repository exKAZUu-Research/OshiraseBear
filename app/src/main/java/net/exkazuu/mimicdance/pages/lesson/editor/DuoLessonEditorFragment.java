package net.exkazuu.mimicdance.pages.lesson.editor;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.common.collect.Lists;

import net.exkazuu.mimicdance.BuildConfig;
import net.exkazuu.mimicdance.CharacterSprite;
import net.exkazuu.mimicdance.Lessons;
import net.exkazuu.mimicdance.R;
import net.exkazuu.mimicdance.interpreter.Interpreter;
import net.exkazuu.mimicdance.models.APIClient;
import net.exkazuu.mimicdance.models.program.Command;
import net.exkazuu.mimicdance.models.program.Program;
import net.exkazuu.mimicdance.Lesson;
import net.exkazuu.mimicdance.pages.lesson.judge.BaseJudgeFragment;
import net.exkazuu.mimicdance.pages.lesson.judge.DuoJudgeFragment;
import net.exkazuu.mimicdance.program.UnrolledProgram;

import java.util.Date;
import java.util.List;

import jp.fkmsoft.android.framework.util.FragmentUtils;

public class DuoLessonEditorFragment extends BaseLessonEditorFragment {

    private static final int SECONDS = 1000;

    private Handler handler;
    private volatile boolean isReady;

    public static DuoLessonEditorFragment newInstance(Lesson lesson) {
        DuoLessonEditorFragment fragment = new DuoLessonEditorFragment();
        lesson.saveToArguments(fragment);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);
        mTabLayout.addTab(mTabLayout.newTab().setText(getString(R.string.tab_duo_action)));
        position2Group.add(Command.GROUP_DUO_ACTION);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        handler = new Handler();
        handler.post(new ConnectionTask());
        changeJudgeButtonState(APIClient.PartnerState.NONE); // デフォで不可にする
    }

    @Override
    public void onPause() {
        handler.removeCallbacksAndMessages(null);
        handler = null;
        super.onPause();
    }

    @Override
    void judgeClicked() {
        if (BuildConfig.OFFLINE_MODE) {
            String partnerCode = Lessons.getCoccoCode(lesson.getLessonNumber(), lesson.getCharacterNumber() ^ 1);
            startJudge(partnerCode);
            return;
        }

        if (isReady == false) {
            isReady = true;
            handler.post(new ConnectionTask());
        }
        judgeButton.setEnabled(false); // 2回押しを防ぐ
    }

    private void startJudge(String partnerCode) {
        isReady = false;

        final String leftCode, rightCode;
        if (lesson.getCharacterNumber() == 0) {
            leftCode = Program.getMultilineCode(mAdapter.getAsArray());
            rightCode = partnerCode;
        } else {
            leftCode = partnerCode;
            rightCode = Program.getMultilineCode(mAdapter.getAsArray());
        }

        BaseJudgeFragment judgeFragment = DuoJudgeFragment.newInstance(lesson, leftCode, rightCode);
        FragmentUtils.toNextFragment(getFragmentManager(), R.id.container, judgeFragment, true, STACK_TAG);
    }

    private void changeJudgeButtonState(APIClient.PartnerState partnerState) {
        if (BuildConfig.OFFLINE_MODE || !partnerState.isNone()) {
            if (isReady) {
                judgeButton.setText(R.string.waiting_partner);
                judgeButton.setEnabled(false);
                waitingMsg.setVisibility(View.VISIBLE);
            } else {
                judgeButton.setText(R.string.check_answer);
                judgeButton.setEnabled(true);
                waitingMsg.setVisibility(View.GONE);
            }
        } else {
            judgeButton.setText(R.string.partner_is_offline);
            judgeButton.setEnabled(false);
            waitingMsg.setVisibility(View.GONE);
        }
    }

    private String getProgram() {
        return Program.getMultilineCode(mAdapter.getAsArray());
    }

    class ConnectionTask implements Runnable {
        @Override
        public void run() {
            // 2個以上のタスクがhandlerにつっこまれてたら、自分以外を消す。
            if (handler == null) return;
            handler.removeCallbacksAndMessages(null);
            if (isReady) {
                new AsyncTask<Void, Void, APIClient.PartnerState>() {

                    @Override
                    protected APIClient.PartnerState doInBackground(Void... params) {
                        if (handler == null) return APIClient.PartnerState.NONE;
                        return APIClient.ready(getContext(), String.valueOf(lesson.getLessonNumber()), getProgram());
                    }

                    @Override
                    protected void onPostExecute(final APIClient.PartnerState partnerState) {
                        if (handler == null) return;

                        Log.v("Mimic", String.format("partnerState:%s, now:%s", partnerState, new Date()));
                        if (partnerState.isReady()) {
                            long rest = partnerState.playAt.getTime() - System.currentTimeMillis();
                            Log.v("Mimic", "restTime: " + rest);
                            if (rest > 0) {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (handler == null) return;
                                        Log.v("Mimic", "startJudge");
                                        startJudge(partnerState.program);
                                    }
                                }, rest);
                            } else {
                                startJudge(partnerState.program);
                            }
                        } else {
                            handler.postDelayed(ConnectionTask.this, 1 * SECONDS);
                            changeJudgeButtonState(partnerState);
                        }
                    }
                }.execute();

            } else {
                new AsyncTask<Void, Void, APIClient.PartnerState>() {

                    @Override
                    protected APIClient.PartnerState doInBackground(Void... params) {
                        if (handler == null) return APIClient.PartnerState.NONE;
                        return APIClient.connect(getContext(), String.valueOf(lesson.getLessonNumber()));
                    }

                    @Override
                    protected void onPostExecute(APIClient.PartnerState partnerState) {
                        if (handler == null) return;

                        long delay = partnerState.isNone() ? 1 * SECONDS : 7 * SECONDS;
                        handler.postDelayed(ConnectionTask.this, delay);
                        changeJudgeButtonState(partnerState);
                    }
                }.execute();
            }
        }
    }

    @Override
    void setCharacterVisibilities() {
        int leftVisibility = lesson.getCharacterNumber() == 0 ? View.VISIBLE : View.INVISIBLE;
        int rightVisibility = lesson.getCharacterNumber() == 1 ? View.VISIBLE : View.INVISIBLE;
        leftCharacterView.setVisibility(leftVisibility);
        userLeftCharacterView.setVisibility(leftVisibility);
        rightCharacterView.setVisibility(rightVisibility);
        userRightCharacterView.setVisibility(rightVisibility);
    }

    @Override
    List<Interpreter> getInterpreters(UnrolledProgram leftProgram, UnrolledProgram rightProgram, CharacterSprite leftCharacterSprite, CharacterSprite rightCharacterSprite) {
        return Lists.newArrayList(
            Interpreter.createForCocco(leftProgram, leftCharacterSprite, 0),
            Interpreter.createForCocco(rightProgram, rightCharacterSprite, 1)
        );
    }
}
