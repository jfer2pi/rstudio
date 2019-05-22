/*
 * ModalDialogBase.java
 *
 * Copyright (C) 2009-19 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.core.client.widget;


import com.google.gwt.animation.client.Animation;
import com.google.gwt.aria.client.DialogRole;
import com.google.gwt.aria.client.Id;
import com.google.gwt.aria.client.Roles;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.*;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.*;
import com.google.gwt.user.client.ui.HasHorizontalAlignment.HorizontalAlignmentConstant;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.Point;
import org.rstudio.core.client.a11y.A11y;
import org.rstudio.core.client.command.ShortcutManager;
import org.rstudio.core.client.command.ShortcutManager.Handle;
import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.dom.NativeWindow;
import org.rstudio.core.client.layout.HorizontalPanelLayout;
import org.rstudio.core.client.layout.VerticalPanelLayout;
import org.rstudio.core.client.theme.res.ThemeStyles;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.ui.RStudioThemes;

import java.util.ArrayList;

public abstract class ModalDialogBase extends DialogBox
{
   protected ModalDialogBase(DialogRole role)
   {
      this(null, role);
   }

   protected ModalDialogBase(SimplePanel containerPanel, DialogRole role)
   {
      // core initialization. passing false for modal works around
      // modal PopupPanel supressing global keyboard accelerators (like
      // Ctrl-N or Ctrl-T). modality is achieved via setGlassEnabled(true)
      super(false, false);
      setGlassEnabled(true);
      addStyleDependentName("ModalDialog");

      // a11y
      role_ = role;
      role_.set(getElement());
      A11y.setARIADialogModal(getElement());
      firstControl_ = new Button("First");
      lastControl_ = new Button("Last");
      Roles.getButtonRole().setAriaHiddenState(firstControl_.getElement(), true);
      Roles.getButtonRole().setAriaHiddenState(lastControl_.getElement(), true);
      DomUtils.visuallyHide(firstControl_.getElement());
      DomUtils.visuallyHide(lastControl_.getElement());

      // main panel used to host UI
      mainPanel_ = new VerticalPanelLayout();
      bottomPanel_ = new HorizontalPanelLayout();
      bottomPanel_.setStyleName(ThemeStyles.INSTANCE.dialogBottomPanel());
      bottomPanel_.setWidth("100%");
      buttonPanel_ = new HorizontalPanelLayout();
      leftButtonPanel_ = new HorizontalPanelLayout();
      bottomPanel_.add(leftButtonPanel_);
      bottomPanel_.add(buttonPanel_);
      bottomPanel_.add(lastControl_);
      setButtonAlignment(HasHorizontalAlignment.ALIGN_RIGHT);
      mainPanel_.add(firstControl_);
      mainPanel_.add(bottomPanel_);

      // embed main panel in a custom container if specified
      containerPanel_ = containerPanel;
      if (containerPanel_ != null)
      {
         containerPanel_.setWidget(mainPanel_);
         setWidget(containerPanel_);
      }
      else
      {
         setWidget(mainPanel_);
      }

      addDomHandler(new KeyDownHandler()
      {
         public void onKeyDown(KeyDownEvent event)
         {
            // Is this too aggressive? Alternatively we could only filter out
            // keycodes that are known to be problematic (pgup/pgdown)
            event.stopPropagation();
         }
      }, KeyDownEvent.getType());

      // Set ARIA role of the underlying DecoratorPanel's <table>, which is child
      // of the SimplePanel's <div>.
      if (DOM.getChildCount(getElement()) != 1)
      {
         Debug.log("ARIA: Unexpected DOM layout in modal dialog (top element child count != 1");
         return;
      }
      if (DOM.getChildCount(DOM.getChild(getElement(), 0)) != 1)
      {
         Debug.log("Unexpected DOM layout in modal dialog (first child's child count != 1");
         return;
      }
      Roles.getPresentationRole().set(DOM.getFirstChild(DOM.getFirstChild(getElement())));

      firstControl_.addFocusHandler(event -> {
         // hidden first control in dialog got focus, wrap around to last control
         focusLastControl();
      });
      
      lastControl_.addFocusHandler(event -> {
         // hidden last control in dialog got focus, wrap around to first control
         focusFirstControl();
      });
   }

   @Override
   protected void beginDragging(MouseDownEvent event)
   {
      // Prevent text selection from occurring when moving the dialog box
      event.preventDefault();
      super.beginDragging(event);
   }

   @Override
   protected void onLoad()
   {
      super.onLoad();
      ModalDialogTracker.onShow(this);
      if (shortcutDisableHandle_ != null)
         shortcutDisableHandle_.close();
      shortcutDisableHandle_ = ShortcutManager.INSTANCE.disable();

      try
      {
         // 728: Focus remains in Source view when message dialog pops up over it
         NativeWindow.get().focus();
      }
      catch (Throwable e)
      {
      }

      RStudioThemes.disableDarkMenus();
   }

   @Override
   protected void onUnload()
   {
      if (shortcutDisableHandle_ != null)
         shortcutDisableHandle_.close();
      shortcutDisableHandle_ = null;

      ModalDialogTracker.onHide(this);

      RStudioThemes.enableDarkMenus();

      super.onUnload();
   }

   public boolean isEscapeDisabled()
   {
      return escapeDisabled_;
   }

   public void setEscapeDisabled(boolean escapeDisabled)
   {
      escapeDisabled_ = escapeDisabled;
   }

   public boolean isEnterDisabled()
   {
      return enterDisabled_;
   }

   public void setEnterDisabled(boolean enterDisabled)
   {
      enterDisabled_ = enterDisabled;
   }

   public void showModal()
   {
      if (mainWidget_ == null)
      {
         mainWidget_ = createMainWidget();

         // get the main widget to line up with the right edge of the buttons.
         mainWidget_.getElement().getStyle().setMarginRight(2, Unit.PX);

         // insert after hidden control used to wrap focus when tabbing through dialog
         mainPanel_.insert(mainWidget_, 1);
      }

      originallyActiveElement_ = DomUtils.getActiveElement();
      if (originallyActiveElement_ != null)
         originallyActiveElement_.blur();

      // position the dialog
      positionAndShowDialog(new Command() {
         @Override
         public void execute()
         {
            // defer shown notification to allow all elements to render
            // before attempting to interact w/ them programatically (e.g. setFocus)
            Timer timer = new Timer() {
               public void run() {
                  onDialogShown();
               }
            };
            timer.schedule(100);
         }
      });
   }
   

   protected abstract Widget createMainWidget();

   protected void positionAndShowDialog(Command onCompleted)
   {
      super.center();
      onCompleted.execute();
   }

   protected void onDialogShown()
   {
   }

   protected void addOkButton(ThemedButton okButton)
   {
      okButton_ = okButton;
      okButton_.addStyleDependentName("DialogAction");
      okButton_.setDefault(defaultOverrideButton_ == null);
      addButton(okButton_);
   }

   protected void setOkButtonCaption(String caption)
   {
      okButton_.setText(caption);
   }
   
   protected void setOkButtonId(String id)
   {
      okButton_.getElement().setId(id);
   }

   protected void enableOkButton(boolean enabled)
   {
      okButton_.setEnabled(enabled);
   }

   protected void clickOkButton()
   {
      okButton_.click();
   }

   protected void setOkButtonVisible(boolean visible)
   {
      okButton_.setVisible(visible);
   }

   protected void focusOkButton()
   {
      if (okButton_ != null)
         FocusHelper.setFocusDeferred(okButton_);
   }

   protected void enableCancelButton(boolean enabled)
   {
      cancelButton_.setEnabled(enabled);
   }

   protected void setDefaultOverrideButton(ThemedButton button)
   {
      if (button != defaultOverrideButton_)
      {
         if (defaultOverrideButton_ != null)
            defaultOverrideButton_.setDefault(false);

         defaultOverrideButton_ = button;
         if (okButton_ != null)
            okButton_.setDefault(defaultOverrideButton_ == null);

         if (defaultOverrideButton_ != null)
            defaultOverrideButton_.setDefault(true);
      }
   }

   protected ThemedButton addCancelButton()
   {
      ThemedButton cancelButton = createCancelButton(null);
      addCancelButton(cancelButton);
      return cancelButton;
   }

   protected ThemedButton createCancelButton(final Operation cancelOperation)
   {
      return new ThemedButton("Cancel", new ClickHandler() {
         public void onClick(ClickEvent event) {
            if (cancelOperation != null)
               cancelOperation.execute();
            closeDialog();
         }
      });
   }

   protected void addCancelButton(ThemedButton cancelButton)
   {
      cancelButton_ = cancelButton;
      cancelButton_.addStyleDependentName("DialogAction");
      addButton(cancelButton_);
   }

   protected void addLeftButton(ThemedButton button)
   {
      button.addStyleDependentName("DialogAction");
      button.addStyleDependentName("DialogActionLeft");
      leftButtonPanel_.add(button);
      allButtons_.add(button);
   }

   protected void addLeftWidget(Widget widget)
   {
      leftButtonPanel_.add(widget);
   }

   protected void removeLeftWidget(Widget widget)
   {
      leftButtonPanel_.remove(widget);
   }

   protected void addButton(ThemedButton button)
   {
      button.addStyleDependentName("DialogAction");
      buttonPanel_.add(button);
      allButtons_.add(button);
      lastVisibleButton_ = button;
   }

   // inserts an action button--in the same panel as OK/cancel, but preceding
   // them (presuming they're already present)
   protected void addActionButton(ThemedButton button)
   {
      button.addStyleDependentName("DialogAction");
      buttonPanel_.insert(button, 0);
      allButtons_.add(button);
   }

   protected void setButtonAlignment(HorizontalAlignmentConstant alignment)
   {
      bottomPanel_.setCellHorizontalAlignment(buttonPanel_, alignment);
   }

   protected ProgressIndicator addProgressIndicator()
   {
      return addProgressIndicator(true);
   }

   protected ProgressIndicator addProgressIndicator(final boolean closeOnCompleted)
   {
      final SlideLabel label = new SlideLabel(true);
      Element labelEl = label.getElement();
      Style labelStyle = labelEl.getStyle();
      labelStyle.setPosition(Style.Position.ABSOLUTE);
      labelStyle.setLeft(0, Style.Unit.PX);
      labelStyle.setRight(0, Style.Unit.PX);
      labelStyle.setTop(-12, Style.Unit.PX);
      mainPanel_.add(label);
      
      return new ProgressIndicator()
      {
         public void onProgress(String message)
         {
            onProgress(message, null);
         }
         
         public void onProgress(String message, Operation onCancel)
         {
            if (message == null)
            {
               label.setText("", true);
               if (showing_)
                  clearProgress();
            }
            else
            {
               label.setText(message, false);
               if (!showing_)
               {
                  enableControls(false);
                  label.show();
                  showing_ = true;
               }
            }
            
            label.onCancel(onCancel);
         }

         public void onCompleted()
         {
            clearProgress();
            if (closeOnCompleted)
               closeDialog();
         }

         public void onError(String message)
         {
            clearProgress();
            RStudioGinjector.INSTANCE.getGlobalDisplay().showErrorMessage(
                  "Error", message);
         }

         @Override
         public void clearProgress()
         {
            if (showing_)
            {
               enableControls(true);
               label.hide();
               showing_ = false;
            }
            
         }
         
         private boolean showing_;
      };
   }

   public void closeDialog()
   {
      hide();
      removeFromParent();

      try
      {
         if (originallyActiveElement_ != null
               && !originallyActiveElement_.getTagName().equalsIgnoreCase("body"))
         {
            Document doc = originallyActiveElement_.getOwnerDocument();
            if (doc != null)
            {
               originallyActiveElement_.focus();
            }
         }
      }
      catch (Exception e)
      {
         // focus() fail if the element is no longer visible. It's
         // easier to just catch this than try to detect it.

         // Also originallyActiveElement_.getTagName() can fail with:
         // "Permission denied to access property 'tagName' from a non-chrome context"
         // possibly due to Firefox "anonymous div" issue.
      }
      originallyActiveElement_ = null;
   }

   protected SimplePanel getContainerPanel()
   {
      return containerPanel_;
   }

   protected void enableControls(boolean enabled)
   {
      enableButtons(enabled);
      onEnableControls(enabled);
   }

   protected void onEnableControls(boolean enabled)
   {

   }

   @Override
   public void onPreviewNativeEvent(Event.NativePreviewEvent event)
   {
      if (!ModalDialogTracker.isTopMost(this))
         return;

      if (event.getTypeInt() == Event.ONKEYDOWN)
      {
         NativeEvent nativeEvent = event.getNativeEvent();
         switch (nativeEvent.getKeyCode())
         {
         case KeyCodes.KEY_ENTER:

            if (enterDisabled_)
               break;

            // allow Enter on textareas or anchors
            Element e = DomUtils.getActiveElement();
            if (e.hasTagName("TEXTAREA") || e.hasTagName("A"))
               return;

            ThemedButton defaultButton = defaultOverrideButton_ == null
                  ? okButton_
                  : defaultOverrideButton_;
            if ((defaultButton != null) && defaultButton.isEnabled())
            {
               nativeEvent.preventDefault();
               nativeEvent.stopPropagation();
               event.cancel();
               defaultButton.click();
            }
            break;
         case KeyCodes.KEY_ESCAPE:
            if (escapeDisabled_)
               break;
            onEscapeKeyDown(event);
            break;
         }
      }
   }

   protected void onEscapeKeyDown(Event.NativePreviewEvent event)
   {
      NativeEvent nativeEvent = event.getNativeEvent();
      if (cancelButton_ == null)
      {
         if ((okButton_ != null) && okButton_.isEnabled())
         {
            nativeEvent.preventDefault();
            nativeEvent.stopPropagation();
            event.cancel();
            okButton_.click();
         }
      }
      else if (cancelButton_.isEnabled())
      {
         nativeEvent.preventDefault();
         nativeEvent.stopPropagation();
         event.cancel();
         cancelButton_.click();
      }
   }

   private void enableButtons(boolean enabled)
   {
      for (int i = 0; i < allButtons_.size(); i++)
         allButtons_.get(i).setEnabled(enabled);
   }

   public void move(Point p, boolean allowAnimation)
   {
      if (!isShowing() || !allowAnimation)
      {
         // Don't animate if not showing
         setPopupPosition(p.getX(), p.getY());
         return;
      }

      if (currentAnimation_ != null)
      {
         currentAnimation_.cancel();
         currentAnimation_ = null;
      }

      final int origLeft = getPopupLeft();
      final int origTop = getPopupTop();
      final int deltaX = p.getX() - origLeft;
      final int deltaY = p.getY() - origTop;

      currentAnimation_ = new Animation()
      {
         @Override
         protected void onUpdate(double progress)
         {
            if (!isShowing())
               cancel();
            else
            {
               setPopupPosition((int) (origLeft + deltaX * progress),
                     (int) (origTop + deltaY * progress));
            }
         }
      };
      currentAnimation_.run(200);
   }

   @Override
   public void setText(String text)
   {
      super.setText(text);
      role_.setAriaLabelProperty(getElement(), text);
   }

   /**
    * Optional description of dialog for accessibility tools 
    * @param element element containing the description
    */
   protected void setARIADescribedBy(Element element)
   {
      role_.setAriaDescribedbyProperty(getElement(), Id.of(element));
   }

   /**
    * Optional description of dialog for accessibility tools; use for multiple elements
    * @param value one or more ids
    */
   protected void setARIADescribedBy(Id... value)
   {
      role_.setAriaDescribedbyProperty(getElement(), value);
   }

   // invoked when user hits Tab key with focus on last control in dialog
   protected void focusFirstControl()
   {
   }
   
   // invoked when user hits Shift+Tab key with focus on first control in dialog
   protected void focusLastControl()
   {
      if (lastVisibleButton_ != null)
         FocusHelper.setFocusDeferred(lastVisibleButton_);
   }

   private Handle shortcutDisableHandle_;

   private boolean escapeDisabled_ = false;
   private boolean enterDisabled_ = false;
   private SimplePanel containerPanel_;
   private VerticalPanelLayout mainPanel_;
   private HorizontalPanelLayout bottomPanel_;
   private HorizontalPanelLayout buttonPanel_;
   private HorizontalPanelLayout leftButtonPanel_;
   private ThemedButton okButton_;
   private ThemedButton cancelButton_;
   private ThemedButton defaultOverrideButton_;
   private ArrayList<ThemedButton> allButtons_ = new ArrayList<ThemedButton>();
   private Widget mainWidget_;
   private com.google.gwt.dom.client.Element originallyActiveElement_;
   private Animation currentAnimation_ = null;
   private DialogRole role_;
   private Button firstControl_;
   private Button lastControl_;
   private ThemedButton lastVisibleButton_;
}
