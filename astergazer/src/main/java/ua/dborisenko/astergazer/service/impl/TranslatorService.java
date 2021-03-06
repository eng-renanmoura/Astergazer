package ua.dborisenko.astergazer.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import ua.dborisenko.astergazer.dao.IContextDao;
import ua.dborisenko.astergazer.dao.IScriptDao;
import ua.dborisenko.astergazer.model.Connection;
import ua.dborisenko.astergazer.model.Context;
import ua.dborisenko.astergazer.model.Extension;
import ua.dborisenko.astergazer.model.Script;
import ua.dborisenko.astergazer.model.block.Block;
import ua.dborisenko.astergazer.exception.BlockNotFoundException;
import ua.dborisenko.astergazer.exception.DaoException;
import ua.dborisenko.astergazer.exception.ServiceException;
import ua.dborisenko.astergazer.service.ITranslatorService;
import ua.dborisenko.astergazer.service.IConfigurationService;

@Service
@Transactional(rollbackFor = Exception.class, readOnly = true)
public class TranslatorService implements ITranslatorService {

    private static final Logger log = LoggerFactory.getLogger(TranslatorService.class);
    private static final ThreadLocal<String> fastAgiHostScope = new ThreadLocal<>();
    private String cachedContexts = "";


    @Autowired
    IConfigurationService configurationService;

    @Autowired
    IScriptDao scriptDao;

    @Autowired
    IContextDao contextDao;

    private Block findStartBlock(Script script) {
        for (Block block : script.getBlocks()) {
            if ("Start".equals(block.getType())) {
                return block;
            }
        }
        throw new BlockNotFoundException("Could not find start block");
    }

    private Block findBlockByLocalId(int localId, Script script) {
        for (Block block : script.getBlocks()) {
            if (block.getLocalId() == localId) {
                return block;
            }
        }
        throw new BlockNotFoundException("Could not find block with local id " + localId);
    }

    private Block findNextBlock(Block block, Script script) {
        for (Connection connection : script.getConnections()) {
            if (connection.getSourceBlockLocalId() == block.getLocalId()) {
                return findBlockByLocalId(connection.getTargetBlockLocalId(), script);
            }
        }
        return null;
    }

    private List<Block> findTrueCaseBlocks(Block switchBlock, Script script) {
        List<Block> result = new ArrayList<>();
        for (Connection connection : script.getConnections()) {
            if (connection.getSourceBlockLocalId() == switchBlock.getLocalId()) {
                Block block = findBlockByLocalId(connection.getTargetBlockLocalId(), script);
                if (block.isCaseBlock()) {
                    result.add(block);
                }
            }
        }
        return result;
    }

    private Block findFalseCaseBlock(Block switchBlock, Script script) {
        for (Connection connection : script.getConnections()) {
            if (connection.getSourceBlockLocalId() == switchBlock.getLocalId()) {
                Block block = findBlockByLocalId(connection.getTargetBlockLocalId(), script);
                if ("FalseCase".equals(block.getType())) {
                    return block;
                }
            }
        }
        throw new BlockNotFoundException("Could not find default case block");
    }

    private void handleBranch(StringBuilder result, Script script, Set<Integer> handledBlocksId,
            Deque<Block> rootBlocks) {
        Block block = rootBlocks.getLast();
        rootBlocks.remove(block);
        while (block != null) {
            if (handledBlocksId.contains(block.getLocalId())) {
                result.append("\tsame = n,Goto(").append(block.getLabel()).append(")\n");
                return;
            }
            if (block.isSwitcher()) {
                handleSwitcher(result, script, handledBlocksId, rootBlocks, block);
                return;
            }
            result.append(block.translate());
            handledBlocksId.add(block.getLocalId());
            block = findNextBlock(block, script);
        }
        result.append("\tsame = n,Hangup()\n");
    }

    private void handleSwitcher(StringBuilder result, Script script, Set<Integer> handledBlocksId,
            Deque<Block> rootBlocks, Block block) {
        List<Block> trueCaseBlocks = findTrueCaseBlocks(block, script);
        Block falseCaseBlock = findFalseCaseBlock(block, script);
        if (block.isAgiComplexBlock()) {
            result.append(block.translate(trueCaseBlocks, fastAgiHostScope.get()));
        } else {
            result.append(block.translate(trueCaseBlocks));
        }
        handledBlocksId.add(block.getLocalId());
        rootBlocks.addAll(trueCaseBlocks);
        rootBlocks.addLast(falseCaseBlock);
    }

    private Script loadScript(Long id) throws ServiceException {
        try {
            return scriptDao.getFull(id);
        } catch (CannotCreateTransactionException | DaoException e) {
            throw new ServiceException("Could not load the script with id " + id, e);
        }
    }

    private String buildScript(Script script) {
        StringBuilder result = new StringBuilder();
        Deque<Block> rootBlocks = new LinkedList<>();
        Set<Integer> handledBlocksId = new HashSet<>();
        rootBlocks.addLast(findStartBlock(script));
        while (rootBlocks.size() > 0) {
            handleBranch(result, script, handledBlocksId, rootBlocks);
        }
        return result.toString();
    }

    @Override
    public String getTranslatedScript(Long id) throws ServiceException {
        fastAgiHostScope.set(configurationService.getFastAgiHost().getValue());
        return buildScript(loadScript(id));
    }

    private void cacheContexts() throws ServiceException {
        try {
            List<Context> contexts = contextDao.getAll();
            for (Context context : contexts) {
                for (Extension extension : context.getExtensions()) {
                    if (extension.getScript() != null) {
                        extension.setScript(loadScript(extension.getScriptId()));
                    }
                }
            }
            cachedContexts = translateContexts(contexts);
        } catch (CannotCreateTransactionException | DaoException e) {
            throw new ServiceException("Could not load the context list", e);
        }

    }

    private String translateContexts(List<Context> contexts) {
        StringBuilder result = new StringBuilder();
        for (Context context : contexts) {
            result.append("[").append(context.getName()).append("]\n");
            for (Extension extension : context.getExtensions()) {
                result.append("exten = ").append(extension.getName()).append(",1,NoOp()\n");
                if (extension.getScript() != null) {
                    result.append(buildScript(extension.getScript()));
                }
            }
        }
        return result.toString();
    }

    @Override
    public String getTranslatedDialplan() {
        StopWatch watch = new StopWatch();
        watch.start();
        fastAgiHostScope.set(configurationService.getFastAgiHost().getValue());
        StringBuilder result = new StringBuilder();
        try {
            cacheContexts();
        } catch (ServiceException e) {
            log.error("Could not load dialplan", e);
            result.append("; WARNING! Could not load dialplan. The cache is used.\n\n");
        }
        result.append(cachedContexts);
        watch.stop();
        result.append(buildSummaryInfo(watch.getTotalTimeMillis()));
        return result.toString();

    }

    private String buildSummaryInfo(long handlingTime) {
        StringBuilder result = new StringBuilder("\n");
        result.append("; Generated by Astergazer in ");
        result.append(handlingTime).append("ms\n");
        result.append("; ");
        result.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss.SSS")));
        return result.toString();
    }
}
