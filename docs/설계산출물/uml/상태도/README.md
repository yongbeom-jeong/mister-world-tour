# Mister World Tour Object State Diagrams

Updated: 2026-10-12

Three native UML state machines, with English model text and neutral black-and-white presentation. Transitions now show only concise events and guards. Repeated work is described inside states or in model documentation.

## Files

- MWT_State_SMSMessage.png / .mdj
- MWT_State_TravelApplicationView.png / .mdj
- MWT_State_SouvenirStockView.png / .mdj
- MWT_ObjectStateDiagrams.mdj: combined editable StarUML project

PNG files have white backgrounds, three-times rendering resolution and 300 dpi metadata. Keep their aspect ratio when inserting them in Word. Use a full page or a landscape page if required for readable labels.

## UML notation and scope

Rounded rectangles are states. A solid circle is the initial pseudostate. A circled solid circle is a final state. Transition text follows trigger [guard] / effect. A do/ compartment describes ongoing work. A transition without a trigger is a completion transition; its guard is checked after the state's work completes. Initial transitions are unguarded and have no trigger. Final states have no outgoing transitions.

Call events bind actual operations in the copied class context. when(...) denotes a change event. Guard names describe conditions on existing inputs, returned results and submission context, rather than new public operations, DTO fields or persistent status attributes. The complete scope and predicate interpretation are stored in each state machine's English documentation.

## SMSMessage

Pending, Success, Failure and Unknown correspond to SMSDeliveryStatus. record is the existing RecordResult(result) call event. Its success, failure and unknown guards select the recorded outcome. Entry actions in the three result states specify the existing operation's attemptCount increment exactly once per result. The Failure self-transition is external and repeats this entry action. The constructor initializes attemptCount to zero. The manager checks CanRetry before each external retry, allowing four total attempts. Success, Unknown or the fourth confirmed failure completes handling without deleting the object.

## TravelApplicationView

Editing, Reviewing, Reviewed, Submitting, Outcome Unknown and Registered describe the screen after ShowApplication initializes it. review binds ReviewApplication(); submit and retry bind SubmitTravelApplication(). valid means an accepted unchanged review. ok and failed partition the review result. saved means confirmed registration; unknown means unresolved commit; retryable means confirmed non-commit with valid review; recheck means confirmed non-commit requiring another review. same request requires the original key and reviewed content. Editing entry invalidates review data without discarding product, configuration or traveler input. A saved application stays successful regardless of SMS failure.

## SouvenirStockView

Receipt Input, Submitting Receipt, Outcome Unknown, Receipt Completed, Refresh Required and Refreshing Stock describe the screen after initial list loading. select binds SelectSouvenir(souvenir); receipt and retry bind RecordSouvenirStockReceipt(); refresh binds LoadSouvenirs(). valid requires a selected item and positive integer quantity. same request retains key, item, quantity and original server context. A new receipt prepares its key once; retries skip preparation. rejected means preparation failure or confirmed non-commit. saved and refreshed distinguish receipt storage from current stock availability; ok and failed partition the subsequent stock query. Receipt success is preserved if refresh fails. when(newReceipt) starts a distinct receipt only after the prior result is confirmed. Input entry never discards an issued key or pending quantity.

## Verification

The existing architecture model and Word document are preserved. StarUML's 59 official validation rules report no errors. Model IDs and references, English text, monochrome output, label overlaps and image margins are checked. Each file uses native editable UML elements rather than an embedded picture.

## Directional layout

All three diagrams use a top-to-bottom primary path with exception, retry and return paths beside it. The latest user-edited standalone models were rearranged in place; state definitions, operation bindings, transition conditions and object IDs are preserved.
