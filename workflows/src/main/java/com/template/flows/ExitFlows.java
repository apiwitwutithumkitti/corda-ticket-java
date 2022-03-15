package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.template.contracts.TicketContract.Commands.Exit;
import com.template.states.TicketState;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public interface ExitFlows {

    @InitiatingFlow
    @StartableByRPC
    class ExitInitiator extends FlowLogic<SignedTransaction> {
        @NotNull
        private final UniqueIdentifier linearId;
        @NotNull
        private final ProgressTracker progressTracker;
        private final static Step GENERATING_TRANSACTION = new Step(
                "Generating transaction based on parameters.");
        private final static Step VERIFYING_TRANSACTION = new Step(
                "Verifying contract constraints.");
        private final static Step SIGNING_TRANSACTION = new Step(
                "Signing transaction with our private key.");
        public final static Step GATHERING_SIGNS = new Step(
                "Gathering the counter party's signature"){
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        public final static Step FINALISING_TRANSACTION = new Step(
                "Obtaining notary signature and recording transaction."){
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };
        @NotNull
        public static ProgressTracker tracker() {
            return new ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION,
                    GATHERING_SIGNS, FINALISING_TRANSACTION);
        }

        public ExitInitiator(@NotNull UniqueIdentifier linearId) {
            this.linearId = linearId;
            this.progressTracker = tracker();
        }

        @Override
        @NotNull
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            progressTracker.setCurrentStep(GENERATING_TRANSACTION);

            final QueryCriteria assetCriteria = new QueryCriteria.LinearStateQueryCriteria(
                    null, Collections.singletonList(linearId.getId()),
                    null, Vault.StateStatus.UNCONSUMED);
            final List<StateAndRef<TicketState>> asset = getServiceHub()
                    .getVaultService()
                    .queryBy(TicketState.class, assetCriteria).getStates();
            final StateAndRef<TicketState> input = asset.get(0);

            final Party notary = input.getState().getNotary();
            final TicketState inputState = input.getState().getData();
            final Command<Exit> commandExit = new Command<>(new Exit()
                    , inputState.getParticipants()
                    .stream().map(it -> it.getOwningKey())
                    .collect(Collectors.toList()));
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addCommand(commandExit)
                    .addInputState(input);

            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            txBuilder.verify(getServiceHub());

            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            final SignedTransaction partlySignedTx = getServiceHub().signInitialTransaction(txBuilder);

            FlowSession otherOwnerSession;
            if (getOurIdentity().equals(inputState.getIssuer())){
                otherOwnerSession = initiateFlow(inputState.getSpectator());
            } else {
                otherOwnerSession = initiateFlow(inputState.getIssuer());
            }
            final SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partlySignedTx,
                    Arrays.asList(otherOwnerSession), CollectSignaturesFlow.Companion.tracker()));
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            return subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(otherOwnerSession)));
        }
    }

    @InitiatedBy(ExitInitiator.class)
    class ExitResponder extends FlowLogic<SignedTransaction> {

        @NotNull
        private final FlowSession otherOwnerSession;
        @NotNull
        private final ProgressTracker progressTracker;
        public final static Step SIGNING_TRANSACTION = new Step("About to sign transaction with our private key.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return SignTransactionFlow.Companion.tracker();
            }
        };
        public final static Step FINALISING_TRANSACTION = new Step("Waiting to record transaction"){
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        @NotNull
        public static ProgressTracker tracker(){
            return new ProgressTracker(SIGNING_TRANSACTION, FINALISING_TRANSACTION);
        }

        public ExitResponder(@NotNull final FlowSession otherOwnerSession) {
           this (otherOwnerSession, tracker());
        }

        public ExitResponder(@NotNull final FlowSession otherOwnerSession, @NotNull final ProgressTracker progressTracker) {
            this.otherOwnerSession = otherOwnerSession;
            this.progressTracker = progressTracker;
        }

        @Override
        @NotNull
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);

            class SignTxFlow extends SignTransactionFlow {
                public SignTxFlow(@NotNull FlowSession otherSideSession, ProgressTracker progressTracker) {
                    super(otherSideSession, progressTracker);
                }

                @Override
                protected void checkTransaction(@NotNull SignedTransaction stx) throws FlowException {
                }
            }
            final SignTxFlow signTxFlow = new SignTxFlow(otherOwnerSession, SignTransactionFlow.Companion.tracker());
            final SecureHash txId = subFlow(signTxFlow).getId();
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            return subFlow(new ReceiveFinalityFlow(otherOwnerSession, txId));
        }
    }
}
