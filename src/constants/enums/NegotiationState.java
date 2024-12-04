package constants.enums;

public enum NegotiationState {
    IDLE,
    WAITING_FOR_PROPOSALS,
    EVALUATING_PROPOSALS,
    SENDING_ACCEPTANCE,
    WAITING_CONFIRMATION,
    FINISHED
}