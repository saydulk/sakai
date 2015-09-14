package org.sakaiproject.gradebookng.tool.panels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.validator.routines.DoubleValidator;
import org.apache.wicket.AttributeModifier;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxCallListener;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.core.util.string.ComponentRenderer;
import org.apache.wicket.extensions.ajax.markup.html.AjaxEditableLabel;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalWindow;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.FeedbackPanel;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.sakaiproject.gradebookng.business.GradeSaveResponse;
import org.sakaiproject.gradebookng.business.GradebookNgBusinessService;
import org.sakaiproject.gradebookng.business.model.GbGradeInfo;
import org.sakaiproject.gradebookng.business.util.FormatHelper;
import org.sakaiproject.gradebookng.tool.pages.GradebookPage;

/**
 * The panel for the cell of a grade item
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
public class GradeItemCellPanel extends Panel {

	private static final long serialVersionUID = 1L;
	
	@SpringBean(name="org.sakaiproject.gradebookng.business.GradebookNgBusinessService")
	protected GradebookNgBusinessService businessService;
		
	IModel<Map<String,Object>> model;
	Map<String,Object> modelData;
	
	AjaxEditableLabel<String> gradeCell;
	
	String comment;
	GradeCellSaveStyle gradeSaveStyle;
	
	final List<GradeCellNotification> notifications = new ArrayList<GradeCellNotification>();
	
	public enum GradeCellNotification {
		IS_EXTERNAL("grade.notifications.isexternal"),
		OVER_LIMIT("grade.notifications.overlimit"),
		HAS_COMMENT("grade.notifications.hascomment"),
		CONCURRENT_EDIT("grade.notifications.concurrentedit"),
		ERROR("grade.notifications.haserror"),
		INVALID("grade.notifications.invalid");

		private String message;

		GradeCellNotification(String message) {
			this.message = message;
		}

		public String getMessage() {
			return this.message;
		}

	}
	
	public GradeItemCellPanel(String id, IModel<Map<String,Object>> model) {
		super(id, model);
		this.model = model;
	}

	@Override
	public void onInitialize() {
		super.onInitialize();
		
		//unpack model
		modelData = (Map<String,Object>) this.model.getObject();
		final Long assignmentId = (Long) modelData.get("assignmentId");
		final Double assignmentPoints = (Double) modelData.get("assignmentPoints");
		final String studentUuid = (String) modelData.get("studentUuid");
		final boolean isExternal = (boolean) modelData.get("isExternal");
		final GbGradeInfo gradeInfo = (GbGradeInfo) modelData.get("gradeInfo");
		
		//note, gradeInfo may be null
		String rawGrade;
		
		if(gradeInfo != null) {
			rawGrade = gradeInfo.getGrade();
			this.comment = gradeInfo.getGradeComment();
		} else {
			rawGrade = "";
			this.comment = "";
		}
		
		//get grade
		final String formattedGrade = FormatHelper.formatGrade(rawGrade);
				
		//if assignment is external, normal label
		if(isExternal){
			add(new Label("grade", Model.of(formattedGrade)));
			getParent().add(new AttributeModifier("class", "gb-external-item-cell"));
			notifications.add(GradeCellNotification.IS_EXTERNAL);
		} else {
			gradeCell = new AjaxEditableLabel<String>("grade", Model.of(formattedGrade)) {
				
				private static final long serialVersionUID = 1L;
				
				private String originalGrade = null;
								
				@Override
				protected String defaultNullLabel() {
					return "&nbsp;";
				}
				
				@Override
				protected void onInitialize() {
					//set original grade, once only
					super.onInitialize();
					this.originalGrade = this.getLabel().getDefaultModelObjectAsString();
					this.addSpecialAttributes();
					
					//check if grade is over limit and mark the cell with the warning class
					if(NumberUtils.toDouble(this.originalGrade) > assignmentPoints) {
						markOverLimit(this);
						notifications.add(GradeCellNotification.OVER_LIMIT);
					}
					
					//check if we have a comment and mark the cell with the comment icon
					if(StringUtils.isNotBlank(comment)) {
						markHasComment(this);
					}
				}
				
				@Override
				protected void onModelChanging() {
					//capture the original grade before it changes
					this.originalGrade = this.getEditor().getValue();
				}
				
				@Override
				protected void onSubmit(final AjaxRequestTarget target) {
					super.onSubmit(target);
					
					String newGrade = this.getEditor().getValue();

					clearNotifications();

					//perform validation here so we can bypass the backend
					DoubleValidator validator = new DoubleValidator();
					
					if(!validator.isValid(newGrade)) {
						markWarning(this);
						this.getLabel().setDefaultModelObject(this.originalGrade);
					} else {
						
						//for concurrency, get the original grade we have in the UI and pass it into the service as a check
						GradeSaveResponse result = businessService.saveGrade(assignmentId, studentUuid, this.originalGrade, newGrade, comment);
						
						//TODO here, add the message
						switch (result) {
							case OK: 
								markSuccessful(this);
							break;
							case ERROR: 
								markError(this);
								//TODO fix this message
								error("oh dear");
							break;
							case OVER_LIMIT:
								markOverLimit(this);
							break;
							case NO_CHANGE:
								//do nothing
							break;
							case CONCURRENT_EDIT:
								markError(this);
								error(getString("error.concurrentedit"));
								notifications.add(GradeCellNotification.CONCURRENT_EDIT);
							break;
							default:
								throw new UnsupportedOperationException("The response for saving the grade is unknown.");
						}


						//format the grade for subsequent display and update the model
						String formattedGrade = FormatHelper.formatGrade(newGrade);
						this.getLabel().setDefaultModelObject(formattedGrade);
					}

					//refresh the components we need
					target.addChildren(getPage(), FeedbackPanel.class);
					target.add(getParentCellFor(this));
					target.add(this);

					refreshNotifications();
				}
				
				@Override
				protected void updateLabelAjaxAttributes(AjaxRequestAttributes attributes) {
					//when switching from editor to label
					Map<String,Object> extraParameters = attributes.getExtraParameters();
					extraParameters.put("assignmentId", assignmentId);
					extraParameters.put("studentUuid", studentUuid);

					AjaxCallListener myAjaxCallListener = new AjaxCallListener() {
						@Override
						public CharSequence getPrecondition(Component component) {
							return "return GradebookWicketEventProxy.updateLabel.handlePrecondition('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs);";
						}
						@Override
						public CharSequence getBeforeSendHandler(Component component) {
							return "GradebookWicketEventProxy.updateLabel.handleBeforeSend('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, settings);";
						}
						@Override
						public CharSequence getSuccessHandler(Component component) {
							return "GradebookWicketEventProxy.updateLabel.handleSuccess('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, data, textStatus);";
						}
						@Override
						public CharSequence getFailureHandler(Component component) {
							return "GradebookWicketEventProxy.updateLabel.handleFailure('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, errorMessage, textStatus);";
						}
						@Override
						public CharSequence getCompleteHandler(Component component) {
							return "GradebookWicketEventProxy.updateLabel.handleComplete('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, textStatus);";
						}
					};
					attributes.getAjaxCallListeners().add(myAjaxCallListener);
				}
				
				@Override
				protected void updateEditorAjaxAttributes(AjaxRequestAttributes attributes) {
					//when switching from label to editor
					Map<String,Object> extraParameters = attributes.getExtraParameters();
					extraParameters.put("assignmentId", assignmentId);
					extraParameters.put("studentUuid", studentUuid);

					AjaxCallListener myAjaxCallListener = new AjaxCallListener() {
						@Override
						public CharSequence getPrecondition(Component component) {
							return "return GradebookWicketEventProxy.updateEditor.handlePrecondition('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs);";
						}
						@Override
						public CharSequence getBeforeSendHandler(Component component) {
							return "GradebookWicketEventProxy.updateEditor.handleBeforeSend('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, settings);";
						}
						@Override
						public CharSequence getSuccessHandler(Component component) {
							return "GradebookWicketEventProxy.updateEditor.handleSuccess('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, data, textStatus);";
						}
						@Override
						public CharSequence getFailureHandler(Component component) {
							return "GradebookWicketEventProxy.updateEditor.handleFailure('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, errorMessage, textStatus);";
						}
						@Override
						public CharSequence getCompleteHandler(Component component) {
							return "GradebookWicketEventProxy.updateEditor.handleComplete('" + getParentCellFor(component.getParent()).getMarkupId() + "', attrs, jqXHR, textStatus);";
						}
					};
					attributes.getAjaxCallListeners().add(myAjaxCallListener);
				}

				private void addSpecialAttributes() {
					Component parentCell = getParentCellFor(this);
					parentCell.add(new AttributeModifier("data-assignmentid", assignmentId));
					parentCell.add(new AttributeModifier("data-studentuuid", studentUuid));
					parentCell.add(new AttributeModifier("class", "gb-grade-item-cell"));
					parentCell.setOutputMarkupId(true); //must output so we can manipulate the classes through ajax
					this.add(new AttributeModifier("class", "gb-ajax-editable-label"));
				}
			};

			gradeCell.setType(String.class);
						
			gradeCell.setOutputMarkupId(true);
						
			add(gradeCell);
		}

		getParent().add(new AttributeModifier("role", "gridcell"));
		getParent().add(new AttributeModifier("aria-readonly", Boolean.toString(isExternal)));

		//menu
		WebMarkupContainer menu = new WebMarkupContainer("menu") {
		
			@Override
			public boolean isVisible() {
				if(isExternal) {
					return false;
				}
				return true;
			}
		};
		
		//grade log
		menu.add(new AjaxLink<Map<String,Object>>("viewGradeLog", model){
			private static final long serialVersionUID = 1L;
			@Override
			public void onClick(AjaxRequestTarget target) {
				
				GradebookPage gradebookPage = (GradebookPage) this.getPage();
				final ModalWindow window = gradebookPage.getGradeLogWindow();
				
				window.setContent(new GradeLogPanel(window.getContentId(), this.getModel(), window));
				window.show(target);
				
			}
		});
		
		//grade comment
		AjaxLink<Map<String,Object>> editGradeComment = new AjaxLink<Map<String,Object>>("editGradeComment", model){
			private static final long serialVersionUID = 1L;
			@Override
			public void onClick(AjaxRequestTarget target) {
				
				GradebookPage gradebookPage = (GradebookPage) this.getPage();
				final ModalWindow window = gradebookPage.getGradeCommentWindow();
				
				final EditGradeCommentPanel panel = new EditGradeCommentPanel(window.getContentId(), this.getModel(), window);
				window.setContent(panel);
				window.showUnloadConfirmation(false);
				window.setWindowClosedCallback(new ModalWindow.WindowClosedCallback() {
					private static final long serialVersionUID = 1L;

					@Override
					public void onClose(AjaxRequestTarget target) {
						comment = panel.getComment();
						
						if(StringUtils.isNotBlank(comment)) {
							markHasComment(gradeCell);
							target.add(getParentCellFor(gradeCell));
							target.appendJavaScript("sakai.gradebookng.spreadsheet.setupCell('" + getParentCellFor(gradeCell).getMarkupId() + "','" + assignmentId + "', '" + studentUuid + "');");
							refreshNotifications();
						};
						
					}
				});
				window.show(target);
			}
		};
		
		//the label changes depending on the state so we wrap it in a model
		IModel<String> editGradeCommentModel = new Model<String>(){
			private static final long serialVersionUID = 1L;

			@Override
			public String getObject() {
				if(StringUtils.isNotBlank(comment)){
					return getString("comment.option.edit");
				} else {
					return getString("comment.option.add");
				}
			}
		};
		
		editGradeComment.add(new Label("editGradeCommentLabel", editGradeCommentModel));
		menu.add(editGradeComment);
		
		add(menu);

		refreshNotifications();
	}
	
	
	/**
	 * Set the enum value so we can use it when we style.
	 * TODO collapse these into one
	 * 
	 */
	private void markSuccessful(Component gradeCell) {
		this.gradeSaveStyle = GradeCellSaveStyle.SUCCESS;
		styleGradeCell(gradeCell);
	}
	
	private void markError(Component gradeCell) {
		this.gradeSaveStyle = GradeCellSaveStyle.ERROR;
		styleGradeCell(gradeCell);
		notifications.add(GradeCellNotification.ERROR);
	}
	
	private void markWarning(Component gradeCell) {
		this.gradeSaveStyle = GradeCellSaveStyle.WARNING;
		styleGradeCell(gradeCell);
		notifications.add(GradeCellNotification.INVALID);
	}
	
	private void markOverLimit(Component gradeCell) {
		this.gradeSaveStyle = GradeCellSaveStyle.OVER_LIMIT;
		styleGradeCell(gradeCell);
		notifications.add(GradeCellNotification.OVER_LIMIT);
	}
	
	private void markHasComment(Component gradeCell) {
		styleGradeCell(gradeCell); //maintains existing save style
		notifications.add(GradeCellNotification.HAS_COMMENT);
	}
	
	private void clearNotifications() {
		notifications.clear();
		if(StringUtils.isNotBlank(comment)) {
			markHasComment(gradeCell);
		}
	}
	
	/**
	 * Builder for styling the cell. Aware of the cell 'save style' as well as if it has comments and adds styles accordingly
	 * @param gradeCell the cell to style
	 */
	private void styleGradeCell(Component gradeCell) {
		
		ArrayList<String> cssClasses = new ArrayList<>();
		cssClasses.add("gb-grade-item-cell"); //always
		if(this.gradeSaveStyle != null) {
			cssClasses.add(gradeSaveStyle.getCss()); //the particular style for this cell that has been computed previously
		}
		
		//replace the cell styles with the new set
		getParentCellFor(gradeCell).add(AttributeModifier.replace("class", StringUtils.join(cssClasses, " ")));
	}
	
	
	/**
	 * Get the parent container for the grade cell so we can style it
	 * @param gradeCell
	 * @return
	 */
	private Component getParentCellFor(Component gradeCell) {
		return gradeCell.getParent().getParent();
	}
	
	/**
	 * Enum for encapsulating the grade cell save css class that is to be applied
	 *
	 */
	enum GradeCellSaveStyle {
		
		SUCCESS("grade-save-success"),
		ERROR("grade-save-error"),
		WARNING("grade-save-warning"),
		OVER_LIMIT("grade-save-over-limit");
		
		private String css;
		
		GradeCellSaveStyle(String css) {
			this.css = css;
		}
		
		public String getCss() {
			return this.css;
		}
	}
	
	
	private void refreshNotifications() {
		WebMarkupContainer commentNotification = new WebMarkupContainer("commentNotification");
		WebMarkupContainer warningNotification = new WebMarkupContainer("warningNotification");
		WebMarkupContainer errorNotification = new WebMarkupContainer("errorNotification");
		WebMarkupContainer overLimitNotification = new WebMarkupContainer("overLimitNotification");

		warningNotification.setVisible(false);
		errorNotification.setVisible(false);
		overLimitNotification.setVisible(false);

		if (StringUtils.isNotBlank(comment)) {
			modelData.put("comment", comment);
			commentNotification.setVisible(true);
			addPopover(commentNotification, Arrays.asList(GradeCellNotification.HAS_COMMENT));
		} else {
			commentNotification.setVisible(false);
		}
		notifications.remove(GradeCellNotification.HAS_COMMENT);

		if (!notifications.isEmpty()) {
			if (notifications.contains(GradeCellNotification.ERROR)) {
				errorNotification.setVisible(true);
				addPopover(errorNotification, notifications);
			} else if (notifications.contains(GradeCellNotification.INVALID) || notifications.contains(GradeCellNotification.CONCURRENT_EDIT)  || notifications.contains(GradeCellNotification.IS_EXTERNAL)) {
				warningNotification.setVisible(true);
				addPopover(warningNotification, notifications);
			} else if (notifications.contains(GradeCellNotification.OVER_LIMIT)) {
				overLimitNotification.setVisible(true);
				addPopover(overLimitNotification, notifications);
			}
		}

		addOrReplace(commentNotification);
		addOrReplace(warningNotification);
		addOrReplace(errorNotification);
		addOrReplace(overLimitNotification);
	}


	private void addPopover(Component component, List<GradeCellNotification> notifications) {
		GradeItemCellPopoverPanel popover = new GradeItemCellPopoverPanel("popover", Model.ofMap(modelData), notifications);
		String popoverString = ComponentRenderer.renderComponent(popover).toString();

		component.add(new AttributeModifier("data-toggle", "popover"));
		component.add(new AttributeModifier("data-trigger", "focus"));
		component.add(new AttributeModifier("data-placement", "bottom"));
		component.add(new AttributeModifier("data-html", "true"));
		component.add(new AttributeModifier("data-container", "#gradebookGrades"));
		component.add(new AttributeModifier("data-content", popoverString));
		component.add(new AttributeModifier("tabindex", "0"));
	}
}
