/**
 * Copyright 2014 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Based on the WalletProtobufSerialiser written by Miron Cuperman, copyright Google (also MIT licence)
 */

package org.multibit.hd.core.store;

import com.google.bitcoin.core.Transaction;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.TextFormat;
import org.multibit.contact.MBHDProtos;
import org.multibit.hd.core.api.Contact;
import org.multibit.hd.core.api.StarStyle;
import org.multibit.hd.core.exceptions.ContactsLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Serialize and de-serialize contacts to a byte stream containing a
 * <a href="http://code.google.com/apis/protocolbuffers/docs/overview.html">protocol buffer</a>. Protocol buffers are
 * a data interchange format developed by Google with an efficient binary representation, a type safe specification
 * language and compilers that generate code to work with those data structures for many languages. Protocol buffers
 * can have their format evolved over time: conceptually they represent data using (tag, length, value) tuples. The
 * format is defined by the <tt>bitcoin.proto</tt> file in the bitcoinj source distribution.<p>
 *
 * This class is used through its static methods. The most common operations are writeContacts and readContacts, which do
 * the obvious operations on Output/InputStreams. You can use a {@link java.io.ByteArrayInputStream} and equivalent
 * {@link java.io.ByteArrayOutputStream} if you'd like byte arrays instead. The protocol buffer can also be manipulated
 * in its object form if you'd like to modify the flattened data structure before serialization to binary.<p>
 * 
 * @author Miron Cuperman
 * @author Jim Burton
 */
public class ContactsProtobufSerializer {
    private static final Logger log = LoggerFactory.getLogger(org.multibit.hd.core.store.ContactsProtobufSerializer.class);


    public ContactsProtobufSerializer() {
    }


    /**
     * Formats the given Contacts to the given output stream in protocol buffer format.<p>
     */
    public void writeContacts(Set<Contact> contacts, OutputStream output) throws IOException {
        MBHDProtos.Contacts contactsProto = contactsToProto(contacts);
        contactsProto.writeTo(output);
    }

    /**
     * Returns the given contacts formatted as text. The text format is that used by protocol buffers and although it
     * can also be parsed using {@link TextFormat#merge(CharSequence, com.google.protobuf.Message.Builder)},
     * it is designed more for debugging than storage. It is not well specified and wallets are largely binary data
     * structures anyway, consisting as they do of keys (large random numbers) and {@link Transaction}s which also
     * mostly contain keys and hashes.
     */
    public String contactsToText(Set<Contact> contacts) {
      MBHDProtos.Contacts contactsProto = contactsToProto(contacts);
        return TextFormat.printToString(contactsProto);
    }

    /**
     * Converts the given contacts to the object representation of the protocol buffers. This can be modified, or
     * additional data fields set, before serialization takes place.
     */
    public MBHDProtos.Contacts contactsToProto(Set<Contact> contacts) {
      MBHDProtos.Contacts.Builder contactsBuilder = MBHDProtos.Contacts.newBuilder();

      Preconditions.checkNotNull(contacts, "Contacts must be specified");

      for (Contact contact : contacts) {
        MBHDProtos.Contact contactProto = makeContactProto(contact);
       contactsBuilder.addContact(contactProto);
      }

      return contactsBuilder.build();
    }

  private static MBHDProtos.Contact makeContactProto(Contact contact) {
    MBHDProtos.Contact.Builder contactBuilder = MBHDProtos.Contact.newBuilder();
    contactBuilder.setId(contact.getId().toString());
    contactBuilder.setName(contact.getName());
    contactBuilder.setBitcoinAddress(contact.getBitcoinAddress().or(""));
    contactBuilder.setEmail(contact.getEmail().or(""));
    contactBuilder.setImagePath(contact.getImagePath().or(""));
    contactBuilder.setExtendedPublicKey(contact.getExtendedPublicKey().or(""));
    contactBuilder.setNotes(contact.getNotes().or(""));
    contactBuilder.setStarStyle(getStarStyleAsInt(contact.getStarStyle()));

    // Construct tags
    List<String> tags = contact.getTags();
    if (tags != null) {
      int tagIndex = 0;
      for (String tag : tags) {
        MBHDProtos.Tag tagProto = makeTagProto(tag);
        contactBuilder.addTag(tagIndex, tagProto);
        tagIndex++;
      }
    }

    return contactBuilder.build();
  }

  private static int getStarStyleAsInt(StarStyle starStyle) {
    if (starStyle == null) {
      return 0; // unknown
    }

    switch(starStyle) {
      case UNKNOWN: return 0;
      case FILL_1: return 1;
      case FILL_2: return 2;
      case FILL_3: return 3;
      case EMPTY: return 4;
      default: return 0;
    }
  }
  private static StarStyle getStarStyleFromInt(int starStyleAsInt) {
     switch(starStyleAsInt) {
       case 0: return StarStyle.UNKNOWN;
       case 1: return StarStyle.FILL_1;
       case 2: return StarStyle.FILL_2;
       case 3: return StarStyle.FILL_3;
       case 4: return StarStyle.EMPTY;
       default: return StarStyle.UNKNOWN;
     }
   }

  private static MBHDProtos.Tag makeTagProto(String tag) {
    MBHDProtos.Tag.Builder tagBuilder = MBHDProtos.Tag.newBuilder();
    tagBuilder.setTagValue(tag);
    return tagBuilder.build();
  }

    /**
     * <p>Parses a Contacts from the given stream, using the provided Contacts instance to load data into.
     * <p>A Contacts db can be unreadable for various reasons, such as inability to open the file, corrupt data, internally
     * inconsistent data, You should always
     * handle {@link org.multibit.hd.core.exceptions.ContactsLoadException} and communicate failure to the user in an appropriate manner.</p>
     *
     * @throws ContactsLoadException thrown in various error conditions (see description).
     */
    public Set<Contact> readContacts(InputStream input) throws ContactsLoadException {
        try {
            MBHDProtos.Contacts contactsProto = parseToProto(input);
            Set<Contact> contacts = Sets.newHashSet();
            readContacts(contactsProto, contacts);
            return contacts;
        } catch (IOException e) {
            throw new ContactsLoadException("Could not parse input stream to protobuf", e);
        }
    }

    /**
     * <p>Loads contacts data from the given protocol buffer and inserts it into the given Set of Contact object.
     *
     * <p>A contact db can be unreadable for various reasons, such as inability to open the file, corrupt data, internally
     * inconsistent data, a wallet extension marked as mandatory that cannot be handled and so on. You should always
     * handle {@link ContactsLoadException} and communicate failure to the user in an appropriate manner.</p>
     *
     * @throws ContactsLoadException thrown in various error conditions (see description).
     */
    private void readContacts(MBHDProtos.Contacts contactsProto, Set<Contact> contacts) throws ContactsLoadException {
      Set<Contact> readContacts = Sets.newHashSet();

      List<MBHDProtos.Contact>contactProtos = contactsProto.getContactList();

      if (contactProtos != null) {
        for (MBHDProtos.Contact contactProto : contactProtos) {
          String idAsString = contactProto.getId();
          UUID id = UUID.fromString(idAsString);

          String name = contactProto.getName();

          Contact contact = new Contact(id, name);

          contact.setEmail(contactProto.getEmail());
          contact.setBitcoinAddress(contactProto.getBitcoinAddress());
          contact.setImagePath(contactProto.getImagePath());
          contact.setExtendedPublicKey(contactProto.getExtendedPublicKey());
          contact.setNotes(contactProto.getNotes());
          contact.setStarStyle(getStarStyleFromInt(contactProto.getStarStyle()));

          // Create tags
          List<String> tags = Lists.newArrayList();
          List<MBHDProtos.Tag> tagProtos = contactProto.getTagList();
          if (tagProtos != null) {
            for (MBHDProtos.Tag tagProto : tagProtos) {
              tags.add(tagProto.getTagValue());
            }
          }
          contact.setTags(tags);
          readContacts.add(contact);
        }
      }

      // Everything read ok - put the new contacts into the passed in contacts object
      contacts.clear();
      contacts.addAll(readContacts);


//        // Read the scrypt parameters that specify how encryption and decryption is performed.
//        if (walletProto.hasEncryptionParameters()) {
//            Protos.ScryptParameters encryptionParameters = walletProto.getEncryptionParameters();
//            wallet.setKeyCrypter(new KeyCrypterScrypt(encryptionParameters));
//        }
//
//        if (walletProto.hasDescription()) {
//            wallet.setDescription(walletProto.getDescription());
//        }
//
//        // Read all keys
//        for (Protos.Key keyProto : walletProto.getKeyList()) {
//            if (!(keyProto.getType() == Protos.Key.Type.ORIGINAL || keyProto.getType() == Protos.Key.Type.ENCRYPTED_SCRYPT_AES)) {
//                throw new UnreadableWalletException("Unknown key type in wallet, type = " + keyProto.getType());
//            }
//
//            byte[] privKey = keyProto.hasPrivateKey() ? keyProto.getPrivateKey().toByteArray() : null;
//            EncryptedPrivateKey encryptedPrivateKey = null;
//            if (keyProto.hasEncryptedPrivateKey()) {
//                Protos.EncryptedPrivateKey encryptedPrivateKeyProto = keyProto.getEncryptedPrivateKey();
//                encryptedPrivateKey = new EncryptedPrivateKey(encryptedPrivateKeyProto.getInitialisationVector().toByteArray(),
//                        encryptedPrivateKeyProto.getEncryptedPrivateKey().toByteArray());
//            }
//
//            byte[] pubKey = keyProto.hasPublicKey() ? keyProto.getPublicKey().toByteArray() : null;
//
//            ECKey ecKey;
//            final KeyCrypter keyCrypter = wallet.getKeyCrypter();
//            if (keyCrypter != null && keyCrypter.getUnderstoodEncryptionType() != EncryptionType.UNENCRYPTED) {
//                // If the key is encrypted construct an ECKey using the encrypted private key bytes.
//                ecKey = new ECKey(encryptedPrivateKey, pubKey, keyCrypter);
//            } else {
//                // Construct an unencrypted private key.
//                ecKey = new ECKey(privKey, pubKey);
//            }
//            ecKey.setCreationTimeSeconds((keyProto.getCreationTimestamp() + 500) / 1000);
//            wallet.addKey(ecKey);
//        }
//
//        List<Script> scripts = Lists.newArrayList();
//        for (Protos.Script protoScript : walletProto.getWatchedScriptList()) {
//            try {
//                Script script =
//                        new Script(protoScript.getProgram().toByteArray(),
//                                protoScript.getCreationTimestamp() / 1000);
//                scripts.add(script);
//            } catch (ScriptException e) {
//                throw new UnreadableWalletException("Unparseable script in wallet");
//            }
//        }
//
//        wallet.addWatchedScripts(scripts);
//
//        // Read all transactions and insert into the txMap.
//        for (Protos.Transaction txProto : walletProto.getTransactionList()) {
//            readTransaction(txProto, wallet.getParams());
//        }
//
//        // Update transaction outputs to point to inputs that spend them
//        for (Protos.Transaction txProto : walletProto.getTransactionList()) {
//            WalletTransaction wtx = connectTransactionOutputs(txProto);
//            wallet.addWalletTransaction(wtx);
//        }
//
//        // Update the lastBlockSeenHash.
//        if (!walletProto.hasLastSeenBlockHash()) {
//            wallet.setLastBlockSeenHash(null);
//        } else {
//            wallet.setLastBlockSeenHash(byteStringToHash(walletProto.getLastSeenBlockHash()));
//        }
//        if (!walletProto.hasLastSeenBlockHeight()) {
//            wallet.setLastBlockSeenHeight(-1);
//        } else {
//            wallet.setLastBlockSeenHeight(walletProto.getLastSeenBlockHeight());
//        }
//        // Will default to zero if not present.
//        wallet.setLastBlockSeenTimeSecs(walletProto.getLastSeenBlockTimeSecs());
//
//        if (walletProto.hasKeyRotationTime()) {
//            wallet.setKeyRotationTime(new Date(walletProto.getKeyRotationTime() * 1000));
//        }
//
//        loadExtensions(wallet, walletProto);
//
//        if (walletProto.hasVersion()) {
//            wallet.setVersion(walletProto.getVersion());
//        }
//
//        // Make sure the object can be re-used to read another wallet without corruption.
//        txMap.clear();
    }

    /**
     * Returns the loaded protocol buffer from the given byte stream. This method is designed for low level work involving the
     * wallet file format itself.
     */
    public static MBHDProtos.Contacts parseToProto(InputStream input) throws IOException {
        return MBHDProtos.Contacts.parseFrom(input);
    }

//    private void readTransaction(Protos.Transaction txProto, NetworkParameters params) throws UnreadableWalletException {
//        Transaction tx = new Transaction(params);
//        if (txProto.hasUpdatedAt()) {
//            tx.setUpdateTime(new Date(txProto.getUpdatedAt()));
//        }
//
//        for (Protos.TransactionOutput outputProto : txProto.getTransactionOutputList()) {
//            BigInteger value = BigInteger.valueOf(outputProto.getValue());
//            byte[] scriptBytes = outputProto.getScriptBytes().toByteArray();
//            TransactionOutput output = new TransactionOutput(params, tx, value, scriptBytes);
//            tx.addOutput(output);
//        }
//
//        for (Protos.TransactionInput transactionInput : txProto.getTransactionInputList()) {
//            byte[] scriptBytes = transactionInput.getScriptBytes().toByteArray();
//            TransactionOutPoint outpoint = new TransactionOutPoint(params,
//                    transactionInput.getTransactionOutPointIndex() & 0xFFFFFFFFL,
//                    byteStringToHash(transactionInput.getTransactionOutPointHash())
//            );
//            TransactionInput input = new TransactionInput(params, tx, scriptBytes, outpoint);
//            if (transactionInput.hasSequence()) {
//                input.setSequenceNumber(transactionInput.getSequence());
//            }
//            tx.addInput(input);
//        }
//
//        for (int i = 0; i < txProto.getBlockHashCount(); i++) {
//            ByteString blockHash = txProto.getBlockHash(i);
//            int relativityOffset = 0;
//            if (txProto.getBlockRelativityOffsetsCount() > 0)
//                relativityOffset = txProto.getBlockRelativityOffsets(i);
//            tx.addBlockAppearance(byteStringToHash(blockHash), relativityOffset);
//        }
//
//        if (txProto.hasLockTime()) {
//            tx.setLockTime(0xffffffffL & txProto.getLockTime());
//        }
//
//        if (txProto.hasPurpose()) {
//            switch (txProto.getPurpose()) {
//                case UNKNOWN: tx.setPurpose(Transaction.Purpose.UNKNOWN); break;
//                case USER_PAYMENT: tx.setPurpose(Transaction.Purpose.USER_PAYMENT); break;
//                case KEY_ROTATION: tx.setPurpose(Transaction.Purpose.KEY_ROTATION); break;
//                default: throw new RuntimeException("New purpose serialization not implemented");
//            }
//        } else {
//            // Old wallet: assume a user payment as that's the only reason a new tx would have been created back then.
//            tx.setPurpose(Transaction.Purpose.USER_PAYMENT);
//        }
//
//        // Transaction should now be complete.
//        Sha256Hash protoHash = byteStringToHash(txProto.getHash());
//        if (!tx.getHash().equals(protoHash))
//            throw new UnreadableWalletException(String.format("Transaction did not deserialize completely: %s vs %s", tx.getHash(), protoHash));
//        if (txMap.containsKey(txProto.getHash()))
//            throw new UnreadableWalletException("Wallet contained duplicate transaction " + byteStringToHash(txProto.getHash()));
//        txMap.put(txProto.getHash(), tx);
//    }
//
//    private WalletTransaction connectTransactionOutputs(org.bitcoinj.wallet.Protos.Transaction txProto) throws UnreadableWalletException {
//        Transaction tx = txMap.get(txProto.getHash());
//        final WalletTransaction.Pool pool;
//        switch (txProto.getPool()) {
//            case DEAD: pool = WalletTransaction.Pool.DEAD; break;
//            case PENDING: pool = WalletTransaction.Pool.PENDING; break;
//            case SPENT: pool = WalletTransaction.Pool.SPENT; break;
//            case UNSPENT: pool = WalletTransaction.Pool.UNSPENT; break;
//            // Upgrade old wallets: inactive pool has been merged with the pending pool.
//            // Remove this some time after 0.9 is old and everyone has upgraded.
//            // There should not be any spent outputs in this tx as old wallets would not allow them to be spent
//            // in this state.
//            case INACTIVE:
//            case PENDING_INACTIVE:
//                pool = WalletTransaction.Pool.PENDING;
//                break;
//            default:
//                throw new UnreadableWalletException("Unknown transaction pool: " + txProto.getPool());
//        }
//        for (int i = 0 ; i < tx.getOutputs().size() ; i++) {
//            TransactionOutput output = tx.getOutputs().get(i);
//            final Protos.TransactionOutput transactionOutput = txProto.getTransactionOutput(i);
//            if (transactionOutput.hasSpentByTransactionHash()) {
//                final ByteString spentByTransactionHash = transactionOutput.getSpentByTransactionHash();
//                Transaction spendingTx = txMap.get(spentByTransactionHash);
//                if (spendingTx == null) {
//                    throw new UnreadableWalletException(String.format("Could not connect %s to %s",
//                            tx.getHashAsString(), byteStringToHash(spentByTransactionHash)));
//                }
//                final int spendingIndex = transactionOutput.getSpentByTransactionIndex();
//                TransactionInput input = checkNotNull(spendingTx.getInput(spendingIndex));
//                input.connect(output);
//            }
//        }
//
//        if (txProto.hasConfidence()) {
//            Protos.TransactionConfidence confidenceProto = txProto.getConfidence();
//            TransactionConfidence confidence = tx.getConfidence();
//            readConfidence(tx, confidenceProto, confidence);
//        }
//
//        return new WalletTransaction(pool, tx);
//    }
//
//    private void readConfidence(Transaction tx, Protos.TransactionConfidence confidenceProto,
//                                TransactionConfidence confidence) throws UnreadableWalletException {
//        // We are lenient here because tx confidence is not an essential part of the wallet.
//        // If the tx has an unknown type of confidence, ignore.
//        if (!confidenceProto.hasType()) {
//            log.warn("Unknown confidence type for tx {}", tx.getHashAsString());
//            return;
//        }
//        ConfidenceType confidenceType;
//        switch (confidenceProto.getType()) {
//            case BUILDING: confidenceType = ConfidenceType.BUILDING; break;
//            case DEAD: confidenceType = ConfidenceType.DEAD; break;
//            // These two are equivalent (must be able to read old wallets).
//            case NOT_IN_BEST_CHAIN: confidenceType = ConfidenceType.PENDING; break;
//            case PENDING: confidenceType = ConfidenceType.PENDING; break;
//            case UNKNOWN:
//                // Fall through.
//            default:
//                confidenceType = ConfidenceType.UNKNOWN; break;
//        }
//        confidence.setConfidenceType(confidenceType);
//        if (confidenceProto.hasAppearedAtHeight()) {
//            if (confidence.getConfidenceType() != ConfidenceType.BUILDING) {
//                log.warn("Have appearedAtHeight but not BUILDING for tx {}", tx.getHashAsString());
//                return;
//            }
//            confidence.setAppearedAtChainHeight(confidenceProto.getAppearedAtHeight());
//        }
//        if (confidenceProto.hasDepth()) {
//            if (confidence.getConfidenceType() != ConfidenceType.BUILDING) {
//                log.warn("Have depth but not BUILDING for tx {}", tx.getHashAsString());
//                return;
//            }
//            confidence.setDepthInBlocks(confidenceProto.getDepth());
//        }
//        if (confidenceProto.hasWorkDone()) {
//            if (confidence.getConfidenceType() != ConfidenceType.BUILDING) {
//                log.warn("Have workDone but not BUILDING for tx {}", tx.getHashAsString());
//                return;
//            }
//            confidence.setWorkDone(BigInteger.valueOf(confidenceProto.getWorkDone()));
//        }
//        if (confidenceProto.hasOverridingTransaction()) {
//            if (confidence.getConfidenceType() != ConfidenceType.DEAD) {
//                log.warn("Have overridingTransaction but not OVERRIDDEN for tx {}", tx.getHashAsString());
//                return;
//            }
//            Transaction overridingTransaction =
//                txMap.get(confidenceProto.getOverridingTransaction());
//            if (overridingTransaction == null) {
//                log.warn("Have overridingTransaction that is not in wallet for tx {}", tx.getHashAsString());
//                return;
//            }
//            confidence.setOverridingTransaction(overridingTransaction);
//        }
//        for (Protos.PeerAddress proto : confidenceProto.getBroadcastByList()) {
//            InetAddress ip;
//            try {
//                ip = InetAddress.getByAddress(proto.getIpAddress().toByteArray());
//            } catch (UnknownHostException e) {
//                throw new UnreadableWalletException("Peer IP address does not have the right length", e);
//            }
//            int port = proto.getPort();
//            PeerAddress address = new PeerAddress(ip, port);
//            address.setServices(BigInteger.valueOf(proto.getServices()));
//            confidence.markBroadcastBy(address);
//        }
//        switch (confidenceProto.getSource()) {
//            case SOURCE_SELF: confidence.setSource(TransactionConfidence.Source.SELF); break;
//            case SOURCE_NETWORK: confidence.setSource(TransactionConfidence.Source.NETWORK); break;
//            case SOURCE_UNKNOWN:
//                // Fall through.
//            default: confidence.setSource(TransactionConfidence.Source.UNKNOWN); break;
//        }
//    }
}
