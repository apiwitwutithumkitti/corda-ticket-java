package com.template.states;

import com.template.contracts.TicketContract;
import org.jetbrains.annotations.NotNull;
import net.corda.core.contracts.BelongsToContract;
import net.corda.core.contracts.LinearState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@BelongsToContract(TicketContract.class)
public class TicketState implements LinearState {
    @NotNull
    private final Party issuer;
    @NotNull
    private final Party spectator;
    @NotNull
    private final int section;
    @NotNull
    private final UniqueIdentifier linearId;

    public TicketState(Party issuer, Party spectator, int section, UniqueIdentifier linearId) {
        if (issuer == null) throw new NullPointerException("Issuer cannot be null");
        if (spectator == null) throw new NullPointerException("spectator cannot be null");
        if (section == 0) throw new NullPointerException("section cannot be null");
        if (linearId == null) throw new NullPointerException("Unique identifier is required");
        this.issuer = issuer;
        this.spectator = spectator;
        this.section = section;
        this.linearId = linearId;
    }

    @NotNull
    @Override
    public List<AbstractParty> getParticipants() {
        return Arrays.asList(issuer, spectator);
    }

    @NotNull
    @Override
    public UniqueIdentifier getLinearId() {
        return linearId;
    }

    @NotNull
    public Party getIssuer() {
        return issuer;
    }

    @NotNull
    public Party getSpectator() {
        return spectator;
    }

    @NotNull
    public int getSection() {
        return section;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TicketState that = (TicketState) o;
        return section == that.section && issuer.equals(that.issuer) && spectator.equals(that.spectator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issuer, spectator, section, linearId);
    }

    @Override
    public String toString() {
        return "TicketState{" +
                "issuer=" + issuer +
                ", spectator=" + spectator +
                ", section=" + section +
                ", linearId=" + linearId +
                '}';
    }
}
