package im.zego.livedemo.feature.room.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.StringUtils;

import im.zego.livedemo.R;

public class CommonDialog extends BaseDialog {

    private TextView tvTitle;
    private TextView tvContent;
    private TextView tvNegative;
    private TextView tvPositive;
    private View viewDivider;

    private String title;
    private String content;
    private String negativeButtonText;
    private OnClickListener negativeButtonListener;
    private String positiveButtonText;
    private OnClickListener positiveButtonListener;

    public CommonDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.dialog_common;
    }

    @Override
    protected void initView() {
        super.initView();
        tvTitle = findViewById(R.id.tv_title);
        tvContent = findViewById(R.id.tv_content);
        tvNegative = findViewById(R.id.tv_negative);
        tvPositive = findViewById(R.id.tv_positive);
        viewDivider = findViewById(R.id.view_divider);
    }

    @Override
    protected void initData() {
        super.initData();
        tvTitle.setText(title);
        tvContent.setText(content);
        if (StringUtils.isTrimEmpty(negativeButtonText)) {
            tvNegative.setVisibility(View.GONE);
            viewDivider.setVisibility(View.GONE);
        } else {
            tvNegative.setText(negativeButtonText);
        }
        tvPositive.setText(positiveButtonText);
    }

    @Override
    protected void initListener() {
        super.initListener();
        if (negativeButtonListener != null) {
            tvNegative.setOnClickListener(v -> {
                negativeButtonListener.onClick(this, DialogInterface.BUTTON_NEGATIVE);
            });
        }
        if (positiveButtonListener != null) {
            tvPositive.setOnClickListener(v -> {
                positiveButtonListener.onClick(this, DialogInterface.BUTTON_POSITIVE);
            });
        }
    }

    @Override
    protected boolean cancelable() {
        return false;
    }

    @Override
    protected boolean canceledOnTouchOutside() {
        return false;
    }

    private void updateParams(
            String title,
            String content,
            String negativeButtonText,
            OnClickListener negativeButtonListener,
            String positiveButtonText,
            OnClickListener positiveButtonListener
    ) {
        this.title = title;
        this.content = content;
        this.negativeButtonText = negativeButtonText;
        this.negativeButtonListener = negativeButtonListener;
        this.positiveButtonText = positiveButtonText;
        this.positiveButtonListener = positiveButtonListener;
    }

    public static class Builder {

        private final Context context;
        private String title;
        private String content;
        private String negativeButtonText;
        private OnClickListener negativeButtonListener;
        private String positiveButtonText;
        private OnClickListener positiveButtonListener;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        public Builder setContent(String content) {
            this.content = content;
            return this;
        }

        public Builder setNegativeButton(String negativeButtonText, OnClickListener listener) {
            this.negativeButtonText = negativeButtonText;
            this.negativeButtonListener = listener;
            return this;
        }

        public Builder setPositiveButton(String positiveButtonText, OnClickListener listener) {
            this.positiveButtonText = positiveButtonText;
            this.positiveButtonListener = listener;
            return this;
        }

        public CommonDialog create() {
            CommonDialog dialog = new CommonDialog(context);
            dialog.updateParams(
                    title,
                    content,
                    negativeButtonText,
                    negativeButtonListener,
                    positiveButtonText,
                    positiveButtonListener
            );
            return dialog;
        }
    }
}